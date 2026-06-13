package com.example.driving.payment;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.payment.service.PaymentService;
import com.example.driving.payment.service.PaymentTxService;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 결제 승인 오케스트레이션 단위 테스트.
 *
 * <p>{@link PaymentService} 의 책임은 분산락 + 흐름 제어(prepare → 외부호출 → applySuccess/applyFailure)이다.
 * DB 작업 검증은 {@link PaymentTxService} 단위 테스트가 담당하므로, 여기서는 {@code paymentTxService} 를
 * mock 으로 두고 <b>외부호출 결과에 따라 어느 TX 메서드가 호출되는지(흐름)</b> 만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("결제 승인 오케스트레이션 단위 테스트")
class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private PaymentTxService paymentTxService;
    @Mock
    private TossPaymentClient tossPaymentClient;

    private static final Long MEMBER_IDX = 100L;
    private static final Long AMOUNT = 150_000L;
    private static final String ORDER_ID = "order-1";
    private static final String PAYMENT_KEY = "pk_test";

    @BeforeEach
    void setUpLock() throws InterruptedException {
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        // 락 실패 케이스는 tryLock 을 따로 오버라이드하고 unlock 경로를 안 타므로 lenient 로 둔다.
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);
    }

    private PaymentConfirmResponse completedResponse() {
        Payment payment = Payment.request(1L, ORDER_ID, AMOUNT, PAYMENT_KEY);
        payment.complete(LocalDateTime.now());
        return PaymentConfirmResponse.from(payment);
    }

    @Test
    @DisplayName("성공 - prepare proceed → 토스 호출 → applySuccess 위임, applyFailure 미호출")
    void confirm_success() {
        given(paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(new PaymentTxService.PreparedResult(false, null));
        TossConfirmResponse toss = new TossConfirmResponse(PAYMENT_KEY, ORDER_ID, "DONE", OffsetDateTime.now());
        given(tossPaymentClient.confirm(any())).willReturn(toss);
        given(paymentTxService.applySuccess(eq(ORDER_ID), any())).willReturn(completedResponse());

        PaymentConfirmResponse response = paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(tossPaymentClient).confirm(any());
        verify(paymentTxService).applySuccess(eq(ORDER_ID), any());
        verify(paymentTxService, never()).applyFailure(anyString(), any());
    }

    @Test
    @DisplayName("멱등(이미 확정) - prepare 가 멱등 반환 → 토스/applySuccess 미호출")
    void confirm_idempotent() {
        given(paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(new PaymentTxService.PreparedResult(true, completedResponse()));

        PaymentConfirmResponse response = paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        verify(tossPaymentClient, never()).confirm(any());
        verify(paymentTxService, never()).applySuccess(anyString(), any());
    }

    @Test
    @DisplayName("토스 거절 - applyFailure 위임 후 400")
    void confirm_tossRejected() {
        given(paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(new PaymentTxService.PreparedResult(false, null));
        given(tossPaymentClient.confirm(any()))
                .willThrow(new TossPaymentException("REJECT_CARD_COMPANY", "한도 초과"));

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("한도 초과")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(paymentTxService).applyFailure(eq(ORDER_ID), any(TossPaymentException.class));
        verify(paymentTxService, never()).applySuccess(anyString(), any());
    }

    @Test
    @DisplayName("게이트웨이 장애 - 503, applyFailure/applySuccess 모두 미호출(상태 변경 없음)")
    void confirm_gatewayUnavailable() {
        given(paymentTxService.prepare(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(new PaymentTxService.PreparedResult(false, null));
        given(tossPaymentClient.confirm(any()))
                .willThrow(new PaymentGatewayUnavailableException("결제 서비스를 일시적으로 사용할 수 없습니다.", new RuntimeException()));

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(paymentTxService, never()).applyFailure(anyString(), any());
        verify(paymentTxService, never()).applySuccess(anyString(), any());
    }

    @Test
    @DisplayName("락 획득 실패 - 409, prepare 미호출")
    void confirm_lockNotAcquired() throws InterruptedException {
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

        assertThatThrownBy(() -> paymentService.confirm(MEMBER_IDX, PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(paymentTxService, never()).prepare(anyLong(), anyString(), anyString(), anyLong());
        verify(tossPaymentClient, never()).confirm(any());
    }
}
