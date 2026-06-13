package com.example.driving.payment.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmRequest;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 토스 결제 승인 오케스트레이션.
 *
 * <p><b>트랜잭션 경계 = 3-TX 분할.</b> 외부 호출(토스 confirm)은 수백 ms~타임아웃이 걸리므로
 * 하나의 트랜잭션으로 감싸면 DB 커넥션을 그동안 점유한다. 그래서
 * TX1(검증·REQUESTED 커밋) → 외부호출(트랜잭션 밖) → TX2(결과 확정 커밋)로 나눈다.
 *
 * <p>이 클래스는 <b>흐름 제어만</b> 담당한다. DB 작업 묶음(TX1·TX2)은 {@link PaymentTxService} 의
 * {@code @Transactional} 메서드로 위임한다 — 별도 빈으로 둬야 Spring AOP 프록시를 경유해
 * 트랜잭션이 적용된다(self-invocation 회피).
 *
 * <p><b>재고 복구는 예약 상태전이(fail) 시점에서만</b> 수행한다(설계 포인트 3). 게이트웨이 장애처럼
 * 결과가 미확정인 경우엔 복구하지 않는다 — 토스 승인됐는데 응답만 유실됐을 수 있어 중복 판매 위험.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String LOCK_KEY_PREFIX = "lock:payment:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 10L; // 토스 read-timeout(5s) + TX 시간 여유

    private final RedissonClient redissonClient;
    private final PaymentTxService paymentTxService;
    private final TossPaymentClient tossPaymentClient;

    /**
     * orderId 단위 분산락으로 confirm 을 직렬화한다. 동시 confirm / 503 후 재시도가 섞여도
     * 같은 주문은 한 번에 하나씩만 처리되어, 토스 confirm 이 중복 호출되지 않는다.
     */
    public PaymentConfirmResponse confirm(Long memberIdx, String paymentKey, String orderId, Long amount) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + orderId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("결제가 처리 중입니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT);
            }
            return doConfirm(memberIdx, paymentKey, orderId, amount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("결제 처리 중 인터럽트가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PaymentConfirmResponse doConfirm(Long memberIdx, String paymentKey, String orderId, Long amount) {
        // TX1: 외부호출 전 — 소유권/멱등/위변조 검증 + REQUESTED 결제행 생성/커밋
        PaymentTxService.PreparedResult prepared = paymentTxService.prepare(memberIdx, paymentKey, orderId, amount);
        if (prepared.idempotent()) {
            log.info("결제 멱등 반환(이미 확정됨) - orderId={}", orderId);
            return prepared.response();
        }

        // 외부호출 — 트랜잭션 밖(DB 커넥션 미점유)
        TossConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirm(new TossConfirmRequest(paymentKey, orderId, amount));
        } catch (TossPaymentException e) {
            // 토스 거절(결과 확정 실패) → TX2: 예약 실패 + 재고 복구 (커밋) 후 실패 통지
            paymentTxService.applyFailure(orderId, e);
            throw new BusinessException(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (PaymentGatewayUnavailableException e) {
            // 결과 미확정 → 상태 변경 없이 503 (재시도 유도). 2차 웹훅/스케줄러가 보정.
            throw new BusinessException(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }

        // TX2: 외부호출 성공 → 예약 확정 + 결제 완료 (커밋)
        return paymentTxService.applySuccess(orderId, tossResponse);
    }
}
