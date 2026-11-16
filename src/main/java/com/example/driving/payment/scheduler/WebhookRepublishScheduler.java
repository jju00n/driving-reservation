package com.example.driving.payment.scheduler;

import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.repository.WebhookEventRepository;
import com.example.driving.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 웹훅 발행 유실 안전망(2차) — 브로커 장애/앱 종료로 Kafka 발행이 실패한 건을 회수한다.
 *
 * <p>수신 단계는 "DB 저장 → Kafka 발행" 두 단계인데, 이 둘은 한 트랜잭션으로 묶이지 않는다(외부
 * 시스템이라 못 묶는다). 저장 후 발행 직전에 죽으면 이벤트가 붕 뜨는데, 그 흔적이 <b>RECEIVED 로
 * 남은 레코드</b>다. 정상 건은 컨슈머가 초 단위로 PROCESSED 로 넘기므로, {@code staleMinutes} 를
 * 넘긴 RECEIVED 는 발행 유실로 보고 다시 발행한다.
 *
 * <p>webhook_events 테이블이 사실상 아웃박스 역할을 한다 — 별도 outbox 테이블을 만드는 대신 이미
 * 감사 목적으로 원문을 보관하던 테이블을 재사용했다. 만료 스케줄러(예약 안전망)와 같은 구조다:
 * 1차는 실시간 경로, 2차는 주기 스캔.
 *
 * <p><b>멀티 인스턴스 주의:</b> 여러 인스턴스면 동시에 돌아 같은 건을 중복 발행할 수 있다. 중복 소비는
 * {@code WebhookService#process} 의 멱등 가드가 막지만, 중복 스캔 비용을 줄이려면 ShedLock/Redisson
 * 분산락으로 단일 실행을 보장하는 것이 좋다(후속 — 만료 스케줄러와 같은 과제).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRepublishScheduler {

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookService webhookService;

    @Value("${webhook.republish.stale-minutes:5}")
    private long staleMinutes;

    @Scheduled(fixedDelayString = "${webhook.republish.scan-interval-ms:60000}")
    public void republishStaleEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleMinutes);
        List<WebhookEvent> stale = webhookEventRepository.findByStatusAndCreatedAtBefore(
                WebhookEventStatus.RECEIVED, threshold);
        if (stale.isEmpty()) {
            return;
        }
        for (WebhookEvent event : stale) {
            try {
                webhookService.republish(event);
            } catch (Exception e) {
                log.error("웹훅 재발행 실패 - webhookEventIdx={}, orderId={}",
                        event.getWebhookEventIdx(), event.getOrderId(), e);
            }
        }
        log.warn("웹훅 재발행 - {}건(RECEIVED 상태로 {}분 초과 잔류)", stale.size(), staleMinutes);
    }
}
