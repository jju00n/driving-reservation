package com.example.driving.payment.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmRequest;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.domain.PaymentHistory;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.domain.ReservationHistory;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.program.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 토스 결제 승인 오케스트레이션.
 *
 * <p><b>트랜잭션 경계 = 3-TX 분할.</b> 외부 호출(토스 confirm)은 수백 ms~타임아웃이 걸리므로
 * {@code @Transactional} 로 감싸면 DB 커넥션을 그동안 점유한다. 그래서
 * TX1(검증·REQUESTED 커밋) → 외부호출(트랜잭션 밖) → TX2(결과 확정 커밋)로 나눈다.
 * 기존 {@link com.example.driving.reservation.service.ReservationService} 의 {@link TransactionTemplate} 패턴을 재사용.
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
    private final TransactionTemplate transactionTemplate;
    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final ScheduleRepository scheduleRepository;
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
        PreparedResult prepared = transactionTemplate.execute(_ -> prepare(memberIdx, paymentKey, orderId, amount));
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
            transactionTemplate.executeWithoutResult(_ -> applyFailure(orderId, e));
            throw new BusinessException(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (PaymentGatewayUnavailableException e) {
            // 결과 미확정 → 상태 변경 없이 503 (재시도 유도). 2차 웹훅/스케줄러가 보정.
            throw new BusinessException(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }

        // TX2: 외부호출 성공 → 예약 확정 + 결제 완료 (커밋)
        final TossConfirmResponse confirmed = tossResponse;
        return transactionTemplate.execute(_ -> applySuccess(orderId, confirmed));
    }

    /** TX1 — 소유권/멱등/위변조 검증 후 REQUESTED 결제행 생성(또는 재시도 시 기존 행 재사용). */
    private PreparedResult prepare(Long memberIdx, String paymentKey, String orderId, Long amount) {
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("결제 대상 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        // 소유권 검증 — 인증 회원과 예약 소유자가 다르면 차단(남의 orderId 로 결제/예약파괴 방지)
        if (!reservation.getMemberIdx().equals(memberIdx)) {
            throw new BusinessException("본인의 예약만 결제할 수 있습니다.", HttpStatus.FORBIDDEN);
        }

        // 중복 confirm 멱등 선처리 — DB 상태를 신뢰원으로 삼아 불필요한 외부호출 제거
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.CONFIRMED) {
            Payment payment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new BusinessException("결제 정보를 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
            return PreparedResult.idempotent(PaymentConfirmResponse.from(payment));
        }
        if (status != ReservationStatus.PAYMENT_PENDING) {
            // PAYMENT_FAILED / CANCELLED / EXPIRED 등 종결 상태
            throw new BusinessException("결제를 진행할 수 없는 예약 상태입니다.", HttpStatus.CONFLICT);
        }

        // amount 위변조 검증 — 반드시 토스 호출 직전(설계 포인트 2: 이중 검증)
        if (!reservation.getAmount().equals(amount)) {
            throw new BusinessException("결제 금액이 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        // 기존 REQUESTED 결제행이 있으면(이전 시도가 게이트웨이 장애 503 등으로 미확정) 재사용해 토스 재confirm 으로 전진.
        // → "503 후 재시도가 uk_order_id 위반으로 영구 409" 데드락 방지.
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            if (!existing.get().getStatus().isRequested()) {
                // 예약은 PENDING 인데 결제행이 종결상태 = 정합성 이상(정상 흐름에선 발생 안 함)
                throw new BusinessException("결제 상태가 올바르지 않습니다. 고객센터에 문의해주세요.", HttpStatus.CONFLICT);
            }
            return PreparedResult.proceed();
        }

        // REQUESTED 결제행 신규 생성. uk_payments_order_id 유니크 제약이 동시 중복 confirm 의 동시성 방어를 겸한다.
        try {
            Payment saved = paymentRepository.save(
                    Payment.request(reservation.getReservationIdx(), orderId, amount, paymentKey));
            paymentHistoryRepository.save(PaymentHistory.of(saved.getPaymentIdx(), PaymentStatus.REQUESTED));
        } catch (DataIntegrityViolationException e) {
            // 동시 confirm 의 패자 — 다른 트랜잭션이 같은 orderId 로 먼저 INSERT 함
            throw new BusinessException("결제가 이미 진행 중입니다.", HttpStatus.CONFLICT);
        }
        return PreparedResult.proceed();
    }

    /** TX2(성공) — 예약 CONFIRMED + 결제 COMPLETED. */
    private PaymentConfirmResponse applySuccess(String orderId, TossConfirmResponse tossResponse) {
        Reservation reservation = findReservation(orderId);
        Payment payment = findPayment(orderId);

        reservation.confirm();
        payment.complete(toLocalDateTime(tossResponse.approvedAt()));

        reservationRepository.save(reservation);
        paymentRepository.save(payment);
        reservationHistoryRepository.save(ReservationHistory.of(reservation.getReservationIdx(), reservation.getStatus()));
        paymentHistoryRepository.save(PaymentHistory.of(payment.getPaymentIdx(), payment.getStatus()));

        log.info("결제 승인 완료 - orderId={}, paymentKey={}", orderId, payment.getPaymentKey());
        return PaymentConfirmResponse.from(payment);
    }

    /** TX2(실패) — 예약 PAYMENT_FAILED + 재고 복구 + 결제 FAILED. */
    private void applyFailure(String orderId, TossPaymentException e) {
        Reservation reservation = findReservation(orderId);
        Payment payment = findPayment(orderId);

        reservation.fail();
        scheduleRepository.increaseRemaining(reservation.getScheduleIdx()); // 재고 복구(상태전이 시점)
        payment.fail(e.getFailureCode(), e.getMessage());

        reservationRepository.save(reservation);
        paymentRepository.save(payment);
        reservationHistoryRepository.save(ReservationHistory.of(reservation.getReservationIdx(), reservation.getStatus()));
        paymentHistoryRepository.save(PaymentHistory.of(payment.getPaymentIdx(), payment.getStatus()));

        log.warn("결제 실패 처리 + 재고 복구 - orderId={}, code={}, message={}",
                orderId, e.getFailureCode(), e.getMessage());
    }

    private Reservation findReservation(String orderId) {
        return reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("결제 대상 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private Payment findPayment(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("결제 정보를 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private LocalDateTime toLocalDateTime(java.time.OffsetDateTime approvedAt) {
        return approvedAt != null ? approvedAt.toLocalDateTime() : LocalDateTime.now();
    }

    /** TX1 결과 — 멱등 종료(이미 확정)인지, 외부호출로 진행할지. */
    private record PreparedResult(boolean idempotent, PaymentConfirmResponse response) {
        static PreparedResult idempotent(PaymentConfirmResponse response) {
            return new PreparedResult(true, response);
        }

        static PreparedResult proceed() {
            return new PreparedResult(false, null);
        }
    }
}
