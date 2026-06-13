package com.example.driving.payment.enums;

/**
 * 웹훅 이벤트 처리 상태. (webhook_events.status)
 */
public enum WebhookEventStatus {
    RECEIVED,   // 수신/저장됨(처리 전)
    PROCESSED,  // 처리 완료
    FAILED      // 처리 중 오류
}
