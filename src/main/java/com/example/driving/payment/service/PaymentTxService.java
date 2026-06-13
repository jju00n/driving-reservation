package com.example.driving.payment.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.domain.PaymentHistory;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.enums.PaymentStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 결제 confirm 의 <b>트랜잭션 경계</b>를 담당하는 빈.
 *
 * <p>외부 호출(토스 confirm)이 낀 3-TX 분할 구조에서, DB 작업 묶음(TX1·TX2)만 이 빈으로 분리한다.
 * 오케스트레이션({@link PaymentService})과 <b>다른 빈</b>으로 두는 이유는 {@code @Transactional} 이
 * Spring AOP 프록시로 동작하기 때문 — 같은 클래스 내부 호출(self-invocation)은 프록시를 거치지 않아
 * 트랜잭션이 적용되지 않는다. 빈을 나눠 프록시를 경유하게 만들어야 어노테이션이 발동한다.
 *
 * <p>각 메서드는 호출자({@code doConfirm})가 트랜잭션 밖에서 부르므로, 기본 전파(REQUIRED)로
 * 호출마다 <b>새 트랜잭션</b>이 열리고 끝나면 커밋된다. {@link BusinessException} 등 RuntimeException 은
 * 기본 롤백 규칙으로 자동 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final ScheduleRepository scheduleRepository;

    /** TX1 — 소유권/멱등/위변조 검증 후 REQUESTED 결제행 생성(또는 재시도 시 기존 행 재사용). */
    @Transactional
    public PreparedResult prepare(Long memberIdx, String paymentKey, String orderId, Long amount) {
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
    @Transactional
    public PaymentConfirmResponse applySuccess(String orderId, TossConfirmResponse tossResponse) {
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
    @Transactional
    public void applyFailure(String orderId, TossPaymentException e) {
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
    public record PreparedResult(boolean idempotent, PaymentConfirmResponse response) {
        static PreparedResult idempotent(PaymentConfirmResponse response) {
            return new PreparedResult(true, response);
        }

        static PreparedResult proceed() {
            return new PreparedResult(false, null);
        }
    }
}
