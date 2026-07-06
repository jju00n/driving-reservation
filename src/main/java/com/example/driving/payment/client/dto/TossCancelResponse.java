package com.example.driving.payment.client.dto;

/**
 * 토스 결제 취소 응답(필요 필드만). 알 수 없는 필드는 Jackson 기본 설정(unknown 무시)으로 통과.
 *
 * <p>전액 취소가 완료되면 {@code status == "CANCELED"} 로 내려온다(부분 취소는 {@code PARTIAL_CANCELED}).
 * 우리는 전액 환불만 하므로 status 확인만으로 충분하다.
 */
public record TossCancelResponse(
        String paymentKey,
        String orderId,
        String status
) {
}
