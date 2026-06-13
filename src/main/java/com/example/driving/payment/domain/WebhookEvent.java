package com.example.driving.payment.domain;

import com.example.driving.payment.enums.WebhookEventStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 토스 웹훅 원문 보관 + 처리 상태 추적. 외부에서 들어오는 이벤트의 감사(audit) 로그이자 멱등 판단 근거.
 * FK 없음 — 예외 상황 대비 안전망이라 참조 무결성보다 유연성 우선(order_id 로 조회).
 */
@Table("webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    private Long webhookEventIdx;
    private String eventType;
    private String orderId;
    private String rawPayload;
    private WebhookEventStatus status;
    private LocalDateTime createdAt;

    public static WebhookEvent received(String eventType, String orderId, String rawPayload) {
        return WebhookEvent.builder()
                .eventType(eventType)
                .orderId(orderId)
                .rawPayload(rawPayload)
                .status(WebhookEventStatus.RECEIVED)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
    }

    public void markFailed() {
        this.status = WebhookEventStatus.FAILED;
    }
}
