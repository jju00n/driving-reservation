package com.example.driving.payment.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹훅 처리 요청 발행. <b>파티션 키는 orderId</b>.
 *
 * <p>키를 orderId 로 잡는 이유: 같은 주문에 EXPIRED → CANCELED 처럼 순서가 있는 이벤트가 올 수 있는데,
 * 키가 없으면 라운드로빈으로 흩어져 파티션이 갈리고 순서가 역전된다. 반대로 주문끼리는 서로 순서를
 * 지킬 이유가 없으므로 다른 파티션에서 병렬 처리된다 — <i>지켜야 할 순서만 지키고 나머지는 병렬로</i>.
 *
 * <p><b>발행 실패는 여기서 막지 않는다.</b> send() 는 비동기이고, 실패해도 토스에는 이미 200 을 준 뒤다.
 * 대신 webhook_events 가 RECEIVED 로 남으므로 {@code WebhookRepublishScheduler}(안전망 2차)가 회수한다.
 * 브로커가 죽었다고 웹훅 응답이 느려지면 안 되기 때문에 고른 방향이다.
 */
@Slf4j
@Component
public class WebhookEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public WebhookEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                @Value("${webhook.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(WebhookMessage message) {
        String payload = objectMapper.writeValueAsString(message);
        kafkaTemplate.send(topic, message.orderId(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // RECEIVED 로 남아 재발행 스케줄러가 주워가므로 여기선 기록만 한다.
                        log.error("웹훅 이벤트 발행 실패 - webhookEventIdx={}, orderId={}",
                                message.webhookEventIdx(), message.orderId(), ex);
                    } else {
                        log.debug("웹훅 이벤트 발행 - orderId={}, partition={}, offset={}",
                                message.orderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
