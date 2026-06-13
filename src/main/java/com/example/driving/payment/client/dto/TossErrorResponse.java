package com.example.driving.payment.client.dto;

/**
 * 토스 4xx 에러 응답 바디. 예: {@code {"code":"REJECT_CARD_COMPANY","message":"한도초과"}}.
 */
public record TossErrorResponse(
        String code,
        String message
) {
}
