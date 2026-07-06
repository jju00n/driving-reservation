package com.example.driving.payment.client.dto;

/**
 * 토스 {@code POST /v1/payments/{paymentKey}/cancel} 요청 바디.
 *
 * <p>{@code cancelAmount} 를 보내지 않으면 <b>전액 취소</b>된다(우리는 전액 환불 정책이라 cancelReason 만 보낸다).
 */
public record TossCancelRequest(
        String cancelReason
) {
}
