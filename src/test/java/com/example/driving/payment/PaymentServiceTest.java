package com.example.driving.payment;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.payment.service.PaymentService;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 승인 서비스 단위 테스트")
class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private TransactionTemplate transactionTemplate;
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
    @Mock
    private TossPaymentClient tossPaymentClient;

    private static final Long MEMBER_IDX = 100L;
    private static final Long SCHEDULE_IDX = 10L;
    private static final Long PROGRAM_IDX = 1L;
    private static final Long AMOUNT = 150_000L;
    private static final String ORDER_ID = "order-1";
    private static final String PAYMENT_KEY = "pk_test";

    @BeforeEach
    void setUpLock() throws InterruptedException {
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
    }

    @SuppressWarnings("unchecked")
    private void stubTxExecute() {
        given(transactionTemplate.execute(any(TransactionCallback.class)))
                .willAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void stubTxWithoutResult() {
        willAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).given(transactionTemplate).executeWithoutResult(any());
    }

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

    @Test
    @DisplayName("성공 - 예약 CONFIRMED/결제 COMPLETED, 재고 복구 미호출")
    void confirm_success() {
        Reservation reservation = pendingReservation();
        Payment payment = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        // prepare: 기존 결제행 없음(신규) → applySuccess: 저장된 결제행 조회
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty(), Optional.of(payment));
        given(paymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(tossPaymentClient.confirm(any()))
                .willReturn(new TossConfirmResponse(PAYMENT_KEY, ORDER_ID, "DONE", OffsetDateTime.now()));
        stubTxExecute();

        PaymentConfirmResponse response = paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(tossPaymentClient).confirm(any());
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }

    @Test
    @DisplayName("소유권 위반 - 다른 회원의 orderId → 403, 토스 미호출")
    void confirm_fail_notOwner() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));
        stubTxExecute();

        Long otherMember = 999L;
        assertThatThrownBy(() -> paymentService.confirm(otherMember, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 예약만")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(tossPaymentClient, never()).confirm(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("금액 위변조 - 400, 토스 미호출, 결제행 미생성")
    void confirm_fail_amountMismatch() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingReservation()));
        stubTxExecute();

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, 999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제 금액이 일치하지 않습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(tossPaymentClient, never()).confirm(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("orderId 없음 - 404")
    void confirm_fail_reservationNotFound() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        stubTxExecute();

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(tossPaymentClient, never()).confirm(any());
    }

    @Test
    @DisplayName("이미 CONFIRMED - 토스 재호출 없이 멱등 반환")
    void confirm_idempotent_alreadyConfirmed() {
        Payment completed = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        completed.complete(LocalDateTime.now());
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservationWithStatus(ReservationStatus.CONFIRMED)));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(completed));
        stubTxExecute();

        PaymentConfirmResponse response = paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(tossPaymentClient, never()).confirm(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("종결 상태(취소/만료) - 409")
    void confirm_fail_terminalStatus() {
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservationWithStatus(ReservationStatus.CANCELLED)));
        stubTxExecute();

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(tossPaymentClient, never()).confirm(any());
    }

    @Test
    @DisplayName("토스 거절 - 예약 PAYMENT_FAILED + 재고 복구 1회 + 400")
    void confirm_fail_tossRejected() {
        Reservation reservation = pendingReservation();
        Payment payment = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty(), Optional.of(payment));
        given(paymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(tossPaymentClient.confirm(any()))
                .willThrow(new TossPaymentException("REJECT_CARD_COMPANY", "한도 초과"));
        stubTxExecute();
        stubTxWithoutResult();

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("한도 초과")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(scheduleRepository).increaseRemaining(SCHEDULE_IDX);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("게이트웨이 장애 - 503, 재고 복구/상태 변경 없음")
    void confirm_fail_gatewayUnavailable() {
        Reservation reservation = pendingReservation();
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        given(paymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(tossPaymentClient.confirm(any()))
                .willThrow(new PaymentGatewayUnavailableException("결제 서비스를 일시적으로 사용할 수 없습니다.", new RuntimeException()));
        stubTxExecute();

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(scheduleRepository, never()).increaseRemaining(anyLong());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("503 후 재시도 - 기존 REQUESTED 결제행 재사용해 토스 재confirm 전진(데드락 방지)")
    void confirm_retry_reusesRequestedPayment() {
        Reservation reservation = pendingReservation();
        Payment requested = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY); // 직전 시도가 남긴 REQUESTED 행
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(requested));
        given(paymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(tossPaymentClient.confirm(any()))
                .willReturn(new TossConfirmResponse(PAYMENT_KEY, ORDER_ID, "DONE", OffsetDateTime.now()));
        stubTxExecute();

        PaymentConfirmResponse response = paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        // 재사용: prepare 에서 신규 INSERT 안 하고 applySuccess 의 complete 저장만(1회). 신규 경로였다면 2회.
        verify(paymentRepository, times(1)).save(any());
        verify(tossPaymentClient).confirm(any());
    }
}
