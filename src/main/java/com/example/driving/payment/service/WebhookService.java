package com.example.driving.payment.service;

import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.repository.WebhookEventRepository;
import com.example.driving.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 토스 웹훅 수신 처리(안전망 1차 — 실시간). 프론트 confirm 누락/유실 대비.
 *
 * <p>현재는 {@code PAYMENT_STATUS_CHANGED} 의 {@code data.status == EXPIRED} 만 의미 처리한다
 * (미결제 만료 → 예약 만료 + 재고 복구). 그 외 이벤트는 원문만 보관한다.
 *
 * <p>멱등 2중: ① 같은 orderId 가 이미 PROCESSED 면 재처리 스킵, ② 실제 만료는
 * {@link ReservationService#expireReservation} 의 원자 UPDATE 가 한 번만 복구하도록 보장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String EVENT_PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final ObjectMapper objectMapper;
    private final WebhookEventRepository webhookEventRepository;
    private final ReservationService reservationService;

    public void handle(String rawPayload) {
        JsonNode root = objectMapper.readTree(rawPayload);
        String eventType = root.path("eventType").asString("");
        JsonNode data = root.path("data");
        String orderId = data.path("orderId").asString(null);
        String dataStatus = data.path("status").asString(null);

        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.received(eventType, orderId, rawPayload));

        try {
            // ① 같은 orderId 가 이미 처리됐으면 재고 복구를 다시 시도하지 않음
            boolean alreadyProcessed = orderId != null
                    && webhookEventRepository.existsByOrderIdAndStatus(orderId, WebhookEventStatus.PROCESSED);

            if (!alreadyProcessed
                    && EVENT_PAYMENT_STATUS_CHANGED.equals(eventType)
                    && STATUS_EXPIRED.equals(dataStatus)
                    && orderId != null) {
                reservationService.expireReservation(orderId); // ② 내부에서 원자 UPDATE 로 멱등 보장
            }

            event.markProcessed();
            webhookEventRepository.save(event);
        } catch (Exception e) {
            event.markFailed();
            webhookEventRepository.save(event);
            log.error("웹훅 처리 실패 - eventType={}, orderId={}", eventType, orderId, e);
            // 토스에는 200 으로 ack(재전송 폭주 방지). 미처리분은 만료 스케줄러(안전망 2차)가 청소.
        }
    }
}
