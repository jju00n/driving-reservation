package com.example.driving.payment.client.dto;

import java.time.OffsetDateTime;

/**
 * 토스 결제 승인 응답(필요 필드만). 알 수 없는 필드는 Jackson 기본 설정(unknown 무시)으로 통과.
 * {@code approvedAt} 은 토스가 오프셋 포함 ISO-8601(예: {@code 2024-01-01T00:00:00+09:00})로 내려준다.
 */
public record TossConfirmResponse(
        String paymentKey,
        String orderId,
        String status,
        OffsetDateTime approvedAt
) {
}
