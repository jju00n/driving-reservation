package com.example.driving.payment.client.dto;

/**
 * 토스 {@code POST /v1/payments/confirm} 요청 바디.
 */
public record TossConfirmRequest(
        String paymentKey,
        String orderId,
        Long amount
) {
}
