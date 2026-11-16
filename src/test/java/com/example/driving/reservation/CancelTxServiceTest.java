package com.example.driving.reservation;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.service.CancelTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 예약 취소/환불 트랜잭션 경계 빈 단위 테스트.
 *
 * <p>분기(멱등/결제전/결제후)·재고 복구 시점·환불 실패 보상 등 DB 작업 로직은 {@link CancelTxService} 의 책임이므로
 * 여기서 검증한다. 프록시 없이 직접 호출하므로 {@code @Transactional} 동작은 통합 테스트가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("예약 취소 트랜잭션 빈 단위 테스트")
class CancelTxServiceTest {

    @InjectMocks
    private CancelTxService cancelTxService;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationHistoryRepository reservationHistoryRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;

    private static final Long MEMBER_IDX = 100L;
    private static final Long RESERVATION_ID = 1L;
    private static final Long SCHEDULE_IDX = 10L;
    private static final Long PROGRAM_IDX = 1L;
    private static final Long PAYMENT_IDX = 50L;
    private static final Long AMOUNT = 150_000L;
    private static final String ORDER_ID = "order-1";
    private static final String PAYMENT_KEY = "pk_test";

    private Reservation reservation(ReservationStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return Reservation.builder()
                .reservationIdx(RESERVATION_ID).memberIdx(MEMBER_IDX).scheduleIdx(SCHEDULE_IDX).programIdx(PROGRAM_IDX)
                .orderId(ORDER_ID).amount(AMOUNT).status(status)
                .reservedAt(now).createdAt(now).updatedAt(now)
                .build();
    }

    /** start_at 이 미래(취소 가능) 스케줄. */
    private Schedule futureSchedule() {
        return schedule(LocalDateTime.now().plusDays(1));
    }

    private Schedule schedule(LocalDateTime startAt) {
        return Schedule.builder()
                .scheduleIdx(SCHEDULE_IDX).programIdx(PROGRAM_IDX)
                .startAt(startAt).endAt(startAt.plusHours(1))
                .capacity(5).remaining(4).status(ScheduleStatus.OPEN)
                .build();
    }

    private Payment completedPayment() {
        Payment payment = Payment.builder()
                .paymentIdx(PAYMENT_IDX).reservationIdx(RESERVATION_ID).orderId(ORDER_ID)
                .paymentKey(PAYMENT_KEY).amount(AMOUNT).status(PaymentStatus.COMPLETED)
                .build();
        return payment;
    }

    // ---------- cancelPrepare (TX1) ----------

    @Test
    @DisplayName("cancelPrepare 예약 없음 - 404")
    void cancelPrepare_notFound() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("cancelPrepare 소유권 위반 - 다른 회원 → 403")
    void cancelPrepare_notOwner() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(ReservationStatus.CONFIRMED)));

        assertThatThrownBy(() -> cancelTxService.cancelPrepare(999L, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("cancelPrepare 이미 CANCELLED - 멱등 반환(상태 변경/재고복구 없음)")
    void cancelPrepare_alreadyCanceled() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(ReservationStatus.CANCELLED)));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        CancelTxService.CancelPreparedResult result = cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(CancelTxService.CancelPreparedResult.Outcome.ALREADY_CANCELED);
        assertThat(result.needsRefund()).isFalse();
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }

    @Test
    @DisplayName("cancelPrepare 결제 전(PAYMENT_PENDING) - 토스 호출 없이 즉시 취소 + 재고 복구")
    void cancelPrepare_pending_canceledWithStockRestore() {
        Reservation pending = reservation(ReservationStatus.PAYMENT_PENDING);
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(pending));
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(futureSchedule()));

        CancelTxService.CancelPreparedResult result = cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(CancelTxService.CancelPreparedResult.Outcome.PENDING_CANCELED);
        assertThat(pending.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(scheduleRepository, times(1)).increaseRemaining(SCHEDULE_IDX);
        verify(paymentRepository, never()).save(any()); // 결제 미터치
    }

    @Test
    @DisplayName("cancelPrepare 결제 완료(CONFIRMED) - 환불 요청 상태로 전이 + NEED_REFUND, 재고 미복구")
    void cancelPrepare_confirmed_needRefund() {
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        Payment payment = completedPayment();
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmed));
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(futureSchedule()));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        CancelTxService.CancelPreparedResult result = cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID);

        assertThat(result.needsRefund()).isTrue();
        assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_REQUESTED);
        assertThat(confirmed.getStatus()).as("예약은 아직 CONFIRMED(전이는 환불 성공 후 TX2)").isEqualTo(ReservationStatus.CONFIRMED);
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }

    @Test
    @DisplayName("cancelPrepare 시작 지난 스케줄 - 409, 결제 미터치")
    void cancelPrepare_afterStart_conflict() {
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmed));
        given(scheduleRepository.findById(SCHEDULE_IDX))
                .willReturn(Optional.of(schedule(LocalDateTime.now().minusMinutes(1))));

        assertThatThrownBy(() -> cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 시작된 프로그램")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelPrepare 종결 상태(PAYMENT_FAILED) - 취소 불가")
    void cancelPrepare_terminalStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(ReservationStatus.PAYMENT_FAILED)));
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(futureSchedule()));

        assertThatThrownBy(() -> cancelTxService.cancelPrepare(MEMBER_IDX, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제 대기 또는 예약 확정");
    }

    // ---------- applyCancel (TX2) ----------

    @Test
    @DisplayName("applyCancel - 예약 CANCELLED + 재고 복구 1회 + 결제 REFUNDED")
    void applyCancel() {
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        Payment payment = completedPayment();
        payment.refundRequest(); // TX1 에서 옮겨둔 상태
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(confirmed));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        cancelTxService.applyCancel(ORDER_ID);

        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(scheduleRepository, times(1)).increaseRemaining(SCHEDULE_IDX);
    }

    @Test
    @DisplayName("applyCancel 멱등 - 이미 CANCELLED+REFUNDED 면 재고 중복 복구 안 함")
    void applyCancel_idempotent() {
        Reservation canceled = reservation(ReservationStatus.CANCELLED);
        Payment refunded = completedPayment();
        refunded.refundRequest();
        refunded.refund();
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(canceled));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(refunded));

        cancelTxService.applyCancel(ORDER_ID);

        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }

    // ---------- cancelRefundFailure (보상) ----------

    @Test
    @DisplayName("cancelRefundFailure - 결제 REFUND_REQUESTED→COMPLETED 원복, 재고 미복구")
    void cancelRefundFailure_restoresPaymentOnly() {
        Payment payment = completedPayment();
        payment.refundRequest();
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        cancelTxService.cancelRefundFailure(ORDER_ID);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelRefundFailure 멱등 - REFUND_REQUESTED 가 아니면 noop")
    void cancelRefundFailure_idempotent() {
        Payment payment = completedPayment(); // COMPLETED (이미 원복됨)
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        cancelTxService.cancelRefundFailure(ORDER_ID);

        verify(paymentRepository, never()).save(any());
    }
}
