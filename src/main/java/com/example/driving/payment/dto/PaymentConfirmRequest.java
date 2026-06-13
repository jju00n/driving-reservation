package com.example.driving.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 토스 결제 승인 요청. 프론트가 successUrl 리다이렉트에서 받은 값을 그대로 전달한다.
 * amount 는 서버가 DB 예약 금액과 다시 대조(위변조 검증)하므로 신뢰하지 않는다.
 */
public record PaymentConfirmRequest(
        @NotBlank String paymentKey,
        @NotBlank String orderId,
        @NotNull @Positive Long amount
) {
}
