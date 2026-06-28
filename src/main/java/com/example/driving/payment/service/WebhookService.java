package com.example.driving.payment.service;

import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.payment.repository.WebhookEventRepository;
import com.example.driving.reservation.service.CancelTxService;
import com.example.driving.reservation.service.ReservationTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 토스 웹훅 수신 처리(안전망 1차 — 실시간). 프론트 confirm 누락/유실 대비.
 *
 * <p>{@code PAYMENT_STATUS_CHANGED} 의 두 상태를 의미 처리한다(그 외 이벤트는 원문만 보관):
 * <ul>
 *   <li>{@code EXPIRED} — 미결제 만료 → 예약 만료 + 재고 복구</li>
 *   <li>{@code CANCELED} — 결제 취소(전액 환불) 완료 통지. 우리가 cancel 호출에서 5xx(미확정)를 받아
 *       {@code REFUND_REQUESTED} 로 잔류시킨 건을 확정(CANCELLED + REFUNDED + 재고 복구)하는 보정 경로.</li>
 * </ul>
 * (국내 카드 결제 취소는 {@code CANCEL_STATUS_CHANGED} 가 아니라 {@code PAYMENT_STATUS_CHANGED}(CANCELED)
 * 로 발송된다 — 토스 웹훅 스펙.)
 *
 * <p>멱등 2중: ① 같은 orderId 가 이미 PROCESSED 면 재처리 스킵, ② 실제 상태 전이는
 * {@link ReservationTxService#expireReservation}(원자 UPDATE) / {@link CancelTxService#applyCancel}(멱등 가드)
 * 가 한 번만 복구하도록 보장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String EVENT_PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_CANCELED = "CANCELED";

    private final ObjectMapper objectMapper;
    private final WebhookEventRepository webhookEventRepository;
    private final ReservationTxService reservationTxService;
    private final PaymentRepository paymentRepository;
    private final CancelTxService cancelTxService;

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
                    && orderId != null) {
                if (STATUS_EXPIRED.equals(dataStatus)) {
                    reservationTxService.expireReservation(orderId); // ② 내부에서 원자 UPDATE 로 멱등 보장
                } else if (STATUS_CANCELED.equals(dataStatus)) {
                    applyCancelIfPendingRefund(orderId); // 환불 미확정(5xx) 잔류 건 보정
                }
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

    /**
     * 결제 취소 완료 웹훅 보정. 우리가 토스 cancel 호출에서 5xx(미확정)를 받아 {@code REFUND_REQUESTED} 로
     * 잔류시킨 건만 대상으로, 토스가 실제 환불을 끝냈음을 확인하고 {@link CancelTxService#applyCancel} 로 확정한다.
     *
     * <p>우리 API 를 안 거친 외부 취소 등 {@code REFUND_REQUESTED} 가 아닌 건은 보정하지 않는다(이번 스코프는
     * 5xx 잔류 건 한정). {@code applyCancel} 은 자체 멱등 가드가 있어 중복 웹훅에도 재고 중복 복구가 없다.
     */
    private void applyCancelIfPendingRefund(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (payment != null && payment.getStatus().isRefundRequested()) {
            cancelTxService.applyCancel(orderId); // CANCELLED + REFUNDED + 재고 복구 (멱등)
            log.info("취소 웹훅 보정 - REFUND_REQUESTED 잔류 건 확정 - orderId={}", orderId);
        }
    }
}
