package com.example.driving.payment.kafka;

import com.example.driving.payment.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 재시도를 다 쓰고 DLT 로 밀린 건의 종착지. 여기 쌓인다는 건 자동 회복이 불가능해 <b>사람이 봐야 한다</b>는 뜻이다.
 *
 * <p>하는 일은 두 가지 — webhook_events 를 FAILED 로 마킹(재발행 스케줄러가 다시 집지 않게)하고,
 * 원인 예외와 함께 에러 로그를 남긴다. 로그는 Grafana 알람으로 이어붙일 수 있다.
 *
 * <p>여기서 재고를 임의 복구하거나 예약 상태를 바꾸지 않는다. 실패 원인을 모른 채 상태를 건드리면
 * 돈과 재고가 어긋난 채로 덮인다 — 격리해두고 원인을 확인한 뒤 재처리하는 편이 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDltListener {

    private final ObjectMapper objectMapper;
    private final WebhookEventRepository webhookEventRepository;

    @KafkaListener(
            topics = "${webhook.kafka.topic}.DLT",
            groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void consumeDlt(
            String payload,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage) {

        log.error("웹훅 처리 최종 실패(DLT) - payload={}, cause={}", payload, exceptionMessage);

        try {
            WebhookMessage message = objectMapper.readValue(payload, WebhookMessage.class);
            webhookEventRepository.findById(message.webhookEventIdx()).ifPresent(event -> {
                event.markFailed();
                webhookEventRepository.save(event);
            });
        } catch (Exception e) {
            // 페이로드 자체가 깨져 idx 를 못 읽는 경우. 위 에러 로그에 원문이 남아 있으므로 여기선 삼킨다
            // (다시 던지면 DLT 의 DLT 를 만들어야 해서 끝이 없다).
            log.error("DLT 페이로드 해석 실패 - payload={}", payload, e);
        }
    }
}
