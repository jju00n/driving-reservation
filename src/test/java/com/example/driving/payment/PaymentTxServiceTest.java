package com.example.driving.payment;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.payment.service.PaymentTxService;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
 * 결제 confirm 의 트랜잭션 경계 빈 단위 테스트.
 *
 * <p>검증/멱등/위변조/재고복구 등 DB 작업 로직은 {@link PaymentTxService} 의 책임이므로 여기서 검증한다.
 * 단위 테스트에서는 프록시 없이 메서드를 직접 호출하므로 {@code @Transactional} 의 트랜잭션 동작은
 * 검증 대상이 아니다(통합 테스트가 담당).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("결제 트랜잭션 빈 단위 테스트")
class PaymentTxServiceTest {

    @InjectMocks
    private PaymentTxService paymentTxService;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationHistoryRepository reservationHistoryRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;
    @Mock
    private ScheduleRepository scheduleRepository;

    private static final Long MEMBER_IDX = 100L;
    private static final Long SCHEDULE_IDX = 10L;
    private static final Long PROGRAM_IDX = 1L;
    private static final Long AMOUNT = 150_000L;
    private static final String ORDER_ID = "order-1";
    private static final String PAYMENT_KEY = "pk_test";

    private Reservation pendingReservation() {
        return Reservation.create(MEMBER_IDX, SCHEDULE_IDX, PROGRAM_IDX, AMOUNT, ORDER_ID);
    }

    private Reservation reservationWithStatus(ReservationStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return Reservation.builder()
                .reservationIdx(1L).memberIdx(MEMBER_IDX).scheduleIdx(SCHEDULE_IDX).programIdx(PROGRAM_IDX)
                .orderId(ORDER_ID).amount(AMOUNT).status(status)
                .reservedAt(now).createdAt(now).updatedAt(now)
                .build();
    }

    // ---------- prepare (TX1) ----------

    @Test
    @DisplayName("prepare 신규 - REQUESTED 결제행 생성 후 proceed")
    void prepare_newPayment_proceed() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        given(paymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        PaymentTxService.PreparedResult result = paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result.idempotent()).isFalse();
        verify(paymentRepository).save(any());
        verify(paymentHistoryRepository).save(any());
    }

    @Test
    @DisplayName("prepare 소유권 위반 - 다른 회원 orderId → 403, 결제행 미생성")
    void prepare_fail_notOwner() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));

        Long otherMember = 999L;
        assertThatThrownBy(() -> paymentTxService.prepare(otherMember, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 예약만")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("prepare 금액 위변조 - 400, 결제행 미생성")
    void prepare_fail_amountMismatch() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));

        assertThatThrownBy(() -> paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, 999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제 금액이 일치하지 않습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("prepare orderId 없음 - 404")
    void prepare_fail_reservationNotFound() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("prepare 이미 CONFIRMED - 멱등 반환(결제행 미생성)")
    void prepare_idempotent_alreadyConfirmed() {
        Payment completed = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        completed.complete(LocalDateTime.now());
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservationWithStatus(ReservationStatus.CONFIRMED)));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(completed));

        PaymentTxService.PreparedResult result = paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result.idempotent()).isTrue();
        assertThat(result.response().status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("prepare 종결 상태(취소/만료) - 409")
    void prepare_fail_terminalStatus() {
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservationWithStatus(ReservationStatus.CANCELLED)));

        assertThatThrownBy(() -> paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("prepare 503 후 재시도 - 기존 REQUESTED 결제행 재사용(신규 INSERT 없이 proceed)")
    void prepare_retry_reusesRequestedPayment() {
        Payment requested = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY); // 직전 시도가 남긴 REQUESTED 행
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(requested));

        PaymentTxService.PreparedResult result = paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(result.idempotent()).isFalse();
        verify(paymentRepository, never()).save(any()); // 재사용 → 신규 INSERT 없음
    }

    // ---------- applySuccess / applyFailure (TX2) ----------

    @Test
    @DisplayName("applySuccess - 예약 CONFIRMED/결제 COMPLETED, 재고 복구 미호출")
    void applySuccess() {
        Reservation reservation = pendingReservation();
        Payment payment = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        PaymentConfirmResponse response = paymentTxService.applySuccess(ORDER_ID,
                new TossConfirmResponse(PAYMENT_KEY, ORDER_ID, "DONE", OffsetDateTime.now()));

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }

    @Test
    @DisplayName("applyFailure - 예약 PAYMENT_FAILED + 재고 복구 1회 + 결제 FAILED")
    void applyFailure() {
        Reservation reservation = pendingReservation();
        Payment payment = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        paymentTxService.applyFailure(ORDER_ID, new TossPaymentException("REJECT_CARD_COMPANY", "한도 초과"));

        verify(scheduleRepository, times(1)).increaseRemaining(SCHEDULE_IDX);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
    }
}
