package com.example.driving.reservation.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.domain.PaymentHistory;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.domain.ReservationHistory;
import com.example.driving.reservation.dto.CancelReservationResponse;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 예약 취소/환불의 <b>트랜잭션 경계</b>를 담당하는 빈. (결제 confirm 의 {@link com.example.driving.payment.service.PaymentTxService}
 * 와 대칭 구조 — 외부호출이 낀 3-TX 분할에서 DB 작업 묶음만 별도 빈의 {@code @Transactional} 메서드로 분리.)
 *
 * <p>오케스트레이션({@link ReservationCancelService})과 다른 빈으로 둬야 Spring AOP 프록시를 경유해
 * 트랜잭션이 적용된다(self-invocation 회피).
 *
 * <p><b>재고 복구는 취소 확정(transition) 시점에만</b> 수행한다. 환불(외부호출) 실패 시에는 좌석을 풀지 않는다
 * — 돈도 못 돌려주고 자리도 잃는 최악을 막기 위함(confirm 실패가 재고를 복구하는 것과 정반대).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelTxService {

    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;
    private final ScheduleRepository scheduleRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    /**
     * TX1 — 외부호출 전 준비/분기. 소유권·시점 검증 후 예약 상태에 따라:
     * <ul>
     *   <li>이미 CANCELLED → 멱등 반환(ALREADY_CANCELED)</li>
     *   <li>PAYMENT_PENDING → 결제 전이라 토스 호출 없이 즉시 취소 + 재고 복구(PENDING_CANCELED)</li>
     *   <li>CONFIRMED → 결제 COMPLETED→REFUND_REQUESTED 로 옮기고 토스 환불 필요 신호(NEED_REFUND)</li>
     * </ul>
     */
    @Transactional
    public CancelPreparedResult cancelPrepare(Long memberIdx, Long reservationId) {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException("취소할 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!reservation.getMemberIdx().equals(memberIdx)) {
            throw new BusinessException("본인의 예약만 취소할 수 있습니다.", HttpStatus.FORBIDDEN);
        }

        ReservationStatus status = reservation.getStatus();

        // 이미 취소됨 → 멱등 반환(중복 취소 요청)
        if (status == ReservationStatus.CANCELLED) {
            Payment payment = paymentRepository.findByOrderId(reservation.getOrderId()).orElse(null);
            return CancelPreparedResult.alreadyCanceled(CancelReservationResponse.of(reservation, payment));
        }

        Schedule schedule = scheduleRepository.findById(reservation.getScheduleIdx())
                .orElseThrow(() -> new BusinessException("스케줄을 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        reservation.ensureCancelable(schedule.getStartAt(), now); // 상태(PENDING/CONFIRMED) + 시점 가드

        // [A] 결제 전 — 토스 호출 없이 즉시 취소 + 재고 복구
        if (status == ReservationStatus.PAYMENT_PENDING) {
            reservation.cancel(now);
            reservationRepository.save(reservation);
            scheduleRepository.increaseRemaining(reservation.getScheduleIdx());
            reservationHistoryRepository.save(
                    ReservationHistory.of(reservation.getReservationIdx(), reservation.getStatus()));
            log.info("결제 전 예약 취소 + 재고 복구 - reservationIdx={}, scheduleIdx={}",
                    reservation.getReservationIdx(), reservation.getScheduleIdx());
            return CancelPreparedResult.pendingCanceled(CancelReservationResponse.of(reservation, null));
        }

        // [B] 결제 완료 — 환불 요청 상태로 옮기고 토스 호출로 전진
        Payment payment = paymentRepository.findByOrderId(reservation.getOrderId())
                .orElseThrow(() -> new BusinessException("결제 정보를 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        payment.refundRequest();
        paymentRepository.save(payment);
        paymentHistoryRepository.save(PaymentHistory.of(payment.getPaymentIdx(), payment.getStatus()));
        return CancelPreparedResult.needRefund(payment.getPaymentKey(), reservation.getOrderId());
    }

    /**
     * TX2(환불 성공/이미취소) — 예약 CANCELLED + 재고 복구 + 결제 REFUNDED.
     * 같은 락 안에서 한 번만 호출되지만, 재시도/동시성 대비 이미 완료된 건은 멱등 반환한다.
     */
    @Transactional
    public CancelReservationResponse applyCancel(String orderId) {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = findReservation(orderId);
        Payment payment = findPayment(orderId);

        // 멱등 — 이미 취소/환불 처리됨(재고 중복 복구 방지)
        if (reservation.getStatus() == ReservationStatus.CANCELLED && payment.getStatus().isRefunded()) {
            return CancelReservationResponse.of(reservation, payment);
        }

        reservation.cancel(now); // 전이(시점 재검증 X — TX1에서 통과)
        scheduleRepository.increaseRemaining(reservation.getScheduleIdx());
        payment.refund();

        reservationRepository.save(reservation);
        paymentRepository.save(payment);
        reservationHistoryRepository.save(ReservationHistory.of(reservation.getReservationIdx(), reservation.getStatus()));
        paymentHistoryRepository.save(PaymentHistory.of(payment.getPaymentIdx(), payment.getStatus()));

        log.info("예약 취소 + 환불 완료 + 재고 복구 - orderId={}, scheduleIdx={}",
                orderId, reservation.getScheduleIdx());
        return CancelReservationResponse.of(reservation, payment);
    }

    /**
     * 환불 "진짜 실패"(취소불가/기한초과/NOT_FOUND/키오류) 보상 — 결제 REFUND_REQUESTED→COMPLETED 되돌림.
     * 예약(CONFIRMED)·재고는 건드리지 않는다(환불 못 했는데 좌석을 풀면 고객이 좌석·돈 모두 잃음).
     */
    @Transactional
    public void cancelRefundFailure(String orderId) {
        Payment payment = findPayment(orderId);
        if (!payment.getStatus().isRefundRequested()) {
            return; // 이미 다른 경로로 정리됨 — 멱등
        }
        payment.cancelRefundRequest();
        paymentRepository.save(payment);
        paymentHistoryRepository.save(PaymentHistory.of(payment.getPaymentIdx(), payment.getStatus()));
        log.warn("환불 실패 → 결제 상태 원복(COMPLETED), 예약/재고 유지 - orderId={}", orderId);
    }

    private Reservation findReservation(String orderId) {
        return reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("취소할 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private Payment findPayment(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException("결제 정보를 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    /** TX1 결과 — 외부호출 없이 끝났는지(완료/멱등) 아니면 토스 환불로 진행할지. */
    public record CancelPreparedResult(
            Outcome outcome,
            CancelReservationResponse response,
            String paymentKey,
            String orderId
    ) {
        public enum Outcome { ALREADY_CANCELED, PENDING_CANCELED, NEED_REFUND }

        static CancelPreparedResult alreadyCanceled(CancelReservationResponse response) {
            return new CancelPreparedResult(Outcome.ALREADY_CANCELED, response, null, null);
        }

        static CancelPreparedResult pendingCanceled(CancelReservationResponse response) {
            return new CancelPreparedResult(Outcome.PENDING_CANCELED, response, null, null);
        }

        static CancelPreparedResult needRefund(String paymentKey, String orderId) {
            return new CancelPreparedResult(Outcome.NEED_REFUND, null, paymentKey, orderId);
        }

        public boolean needsRefund() {
            return outcome == Outcome.NEED_REFUND;
        }
    }
}
