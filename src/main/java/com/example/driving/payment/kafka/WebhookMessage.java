package com.example.driving.payment.kafka;

/**
 * 웹훅 처리 요청 메시지. 수신 단계에서 원문을 파싱해 <b>처리에 필요한 값만</b> 담아 발행한다.
 *
 * <p>원문(rawPayload)은 담지 않는다 — 이미 {@code webhook_events.raw_payload} 에 보관돼 있고,
 * 메시지에 중복으로 실으면 브로커 저장량만 늘기 때문. 감사(audit)가 필요하면 idx 로 DB 를 본다.
 *
 * @param webhookEventIdx webhook_events PK. 처리 완료 마킹 대상
 * @param eventType       PAYMENT_STATUS_CHANGED 등
 * @param orderId         토스 주문번호. <b>파티션 키</b> — 같은 주문의 이벤트 순서를 보장한다
 * @param dataStatus      data.status (EXPIRED / CANCELED 등)
 */
public record WebhookMessage(
        Long webhookEventIdx,
        String eventType,
        String orderId,
        String dataStatus
) {
}
