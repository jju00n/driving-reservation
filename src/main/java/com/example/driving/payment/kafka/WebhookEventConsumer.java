package com.example.driving.payment.kafka;

import com.example.driving.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹훅 처리 컨슈머.
 *
 * <p><b>예외를 잡지 않는다.</b> 이 메서드가 정상 반환해야만 오프셋이 커밋된다(ack-mode: record).
 * 예외가 나가면 커밋되지 않아 재소비되고, {@code DefaultErrorHandler} 의 백오프 재시도를 거쳐
 * 소진되면 DLT 로 격리된다. try-catch 로 감싸는 순간 "처리 성공"으로 커밋돼 이벤트가 사라진다 —
 * 이전 동기 구조의 실패 원인이 정확히 그것이었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventConsumer {

    private final ObjectMapper objectMapper;
    private final WebhookService webhookService;

    @KafkaListener(
            topics = "${webhook.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) {
        WebhookMessage message = objectMapper.readValue(payload, WebhookMessage.class);
        log.debug("웹훅 이벤트 소비 - webhookEventIdx={}, orderId={}",
                message.webhookEventIdx(), message.orderId());
        webhookService.process(message);
    }
}
