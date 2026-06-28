package com.example.driving.reservation.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossCancelRequest;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.reservation.dto.CancelReservationResponse;
import com.example.driving.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 예약 취소/환불 오케스트레이션 — 결제 confirm 의 {@link com.example.driving.payment.service.PaymentService}
 * 와 대칭 구조(분산락 + 외부호출 + 3-TX 분할).
 *
 * <p><b>분산락 키는 confirm 과 동일한 {@code lock:payment:{orderId}}</b> 를 공유한다 — 같은 주문의
 * confirm 과 cancel 이 동시에 들어와도 직렬화되어 상태 경쟁이 발생하지 않는다.
 *
 * <p><b>환불 실패 처리(설계 핵심):</b>
 * <ul>
 *   <li>4xx {@code ALREADY_CANCELED_PAYMENT} = 이미 환불됨(멱등) → DB 를 CANCELLED+REFUNDED+재고복구로 맞춤</li>
 *   <li>4xx 그 외(취소불가/기한초과/NOT_FOUND/키오류) = 진짜 실패 → 결제 상태 원복, 예약/재고 유지(좌석·돈 모두 잃는 위험 방지)</li>
 *   <li>5xx/타임아웃/서킷오픈 = 미확정 → 상태·재고 그대로(REFUND_REQUESTED 잔류), 503 으로 재시도 유도</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private static final String LOCK_KEY_PREFIX = "lock:payment:"; // confirm 과 공유
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 10L; // 토스 read-timeout(5s) + TX 시간 여유
    private static final String CANCEL_REASON = "고객 요청에 의한 예약 취소";
    private static final String ALREADY_CANCELED_CODE = "ALREADY_CANCELED_PAYMENT"; // 토스: 이미 취소된 결제

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final CancelTxService cancelTxService;
    private final TossPaymentClient tossPaymentClient;

    public CancelReservationResponse cancel(Long memberIdx, Long reservationId) {
        // 락 키(orderId) 확보용 사전 조회. 소유권/상태 정식 검증은 락 안 TX1(cancelPrepare)에서 수행.
        String orderId = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException("취소할 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND))
                .getOrderId();

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + orderId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("취소가 처리 중입니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT);
            }
            return doCancel(memberIdx, reservationId, orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("취소 처리 중 인터럽트가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private CancelReservationResponse doCancel(Long memberIdx, Long reservationId, String orderId) {
        // TX1: 검증 + 분기. [A]/멱등은 외부호출 없이 여기서 종료.
        CancelTxService.CancelPreparedResult prepared = cancelTxService.cancelPrepare(memberIdx, reservationId);
        if (!prepared.needsRefund()) {
            return prepared.response();
        }

        // [B] 외부호출 — 토스 환불 (트랜잭션 밖, DB 커넥션 미점유). 멱등키로 중복 취소 방지.
        String idempotencyKey = "cancel-" + orderId;
        try {
            tossPaymentClient.cancel(prepared.paymentKey(), new TossCancelRequest(CANCEL_REASON), idempotencyKey);
            // TX2: 환불 성공 → 예약 CANCELLED + 재고 복구 + 결제 REFUNDED
            return cancelTxService.applyCancel(orderId);
        } catch (TossPaymentException e) {
            if (ALREADY_CANCELED_CODE.equals(e.getFailureCode())) {
                // 이미 환불됨(멱등) → "실패"가 아니라 성공으로 간주, DB 를 맞춰준다
                log.info("토스 이미 취소된 결제 - 멱등 보정 진행 - orderId={}", orderId);
                return cancelTxService.applyCancel(orderId);
            }
            // 진짜 실패 → 결제 상태 원복(COMPLETED), 예약/재고 유지
            cancelTxService.cancelRefundFailure(orderId);
            throw new BusinessException("결제 취소에 실패했습니다. 고객센터에 문의해주세요.", HttpStatus.CONFLICT);
        } catch (PaymentGatewayUnavailableException e) {
            // 미확정 → 상태 변경 없이 503(재시도 유도). REFUND_REQUESTED 잔류, 재고 복구 안 함.
            throw new BusinessException(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
