package com.example.driving.payment.service;

import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.kafka.WebhookEventProducer;
import com.example.driving.payment.kafka.WebhookMessage;
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
 * <p><b>수신과 처리를 나눈다.</b>
 * <ul>
 *   <li>{@link #receive} — 원문 파싱 + RECEIVED 저장 + Kafka 발행까지만. 토스에는 즉시 200</li>
 *   <li>{@link #process} — 컨슈머가 호출. 실제 상태 전이. <b>실패하면 예외를 던져야</b> 재시도된다</li>
 * </ul>
 *
 * <p>나눈 이유는 성능이 아니라 <b>실패한 이벤트를 다시 볼 방법이 없었기 때문</b>이다. 이전 구조는
 * 처리 중 예외가 나면 FAILED 한 줄만 남기고 토스에는 200 을 줬다. 토스는 재전송하지 않고, 만료
 * 스케줄러는 PAYMENT_PENDING 예약만 훑으므로 환불 보정 실패 건(REFUND_REQUESTED 잔류)은 아무도
 * 다시 집지 않았다 — 돈은 환불됐는데 예약은 살아 있는 상태로 영구 잔류.
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
 * <p><b>멱등 3중:</b> ① 같은 webhook_event 재소비 시 PROCESSED 면 스킵, ② 같은 orderId 가 이미
 * PROCESSED 면 스킵, ③ 실제 상태 전이는 {@link ReservationTxService#expireReservation}(원자 UPDATE) /
 * {@link CancelTxService#applyCancel}(멱등 가드)가 한 번만 복구하도록 보장. Kafka 는 at-least-once
 * 라 중복 소비가 필연인데, 토스 재전송을 막으려고 만들어둔 이 가드들이 그대로 쓰인다.
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
    private final WebhookEventProducer webhookEventProducer;

    /**
     * 수신 단계. 원문 보관과 발행까지만 하고 즉시 반환한다 — 토스가 응답을 기다리는 구간이라
     * DB 트랜잭션·외부 호출을 여기서 하지 않는다(느려지면 토스가 타임아웃 → 재전송 → 더 느려지는 증폭).
     *
     * <p>파싱 자체가 실패한 건은 재시도해도 같은 결과이므로 발행하지 않고 FAILED 로 종결한다.
     */
    public void receive(String rawPayload) {
        Parsed parsed = parse(rawPayload);

        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.received(parsed.eventType(), parsed.orderId(), rawPayload));

        if (!parsed.valid()) {
            event.markFailed();
            webhookEventRepository.save(event);
            return;
        }

        webhookEventProducer.publish(toMessage(event.getWebhookEventIdx(), parsed));
    }

    /**
     * 발행 실패로 RECEIVED 에 머문 건을 다시 발행한다({@code WebhookRepublishScheduler} 전용).
     *
     * <p>원래 발행이 뒤늦게 성공했다면 같은 이벤트가 두 번 소비되는데, {@link #process} 의 멱등 가드가
     * 막으므로 재고가 두 번 복구되지 않는다. 유실보다 중복이 낫다는 쪽으로 기운 선택.
     */
    public void republish(WebhookEvent event) {
        Parsed parsed = parse(event.getRawPayload());
        if (!parsed.valid()) {
            event.markFailed();
            webhookEventRepository.save(event);
            return;
        }
        webhookEventProducer.publish(toMessage(event.getWebhookEventIdx(), parsed));
    }

    private WebhookMessage toMessage(Long webhookEventIdx, Parsed parsed) {
        return new WebhookMessage(webhookEventIdx, parsed.eventType(), parsed.orderId(), parsed.dataStatus());
    }

    private Parsed parse(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode data = root.path("data");
            return new Parsed(
                    root.path("eventType").asString(""),
                    data.path("orderId").asString(null),
                    data.path("status").asString(null),
                    true);
        } catch (Exception e) {
            log.error("웹훅 원문 파싱 실패 - payload={}", rawPayload, e);
            return new Parsed("", null, null, false);
        }
    }

    /** 원문에서 뽑은 처리용 값. {@code valid=false} 면 재시도해도 같은 결과라 발행하지 않는다. */
    private record Parsed(String eventType, String orderId, String dataStatus, boolean valid) {
    }

    /**
     * 처리 단계. 컨슈머에서 호출한다.
     *
     * <p><b>예외를 삼키지 않는다.</b> 던져야 오프셋이 커밋되지 않고 재시도 → 최종적으로 DLT 로 간다.
     * 이전 구조에서 catch 로 막아버린 게 유실의 원인이었다.
     *
     * <p>의미 처리 대상이 아니거나 중복인 건도 <b>PROCESSED 로 마킹</b>한다. RECEIVED 로 남겨두면
     * 재발행 스케줄러가 계속 다시 발행해 무한 순환에 빠지기 때문.
     */
    public void process(WebhookMessage message) {
        WebhookEvent event = webhookEventRepository.findById(message.webhookEventIdx())
                .orElse(null);
        if (event == null) {
            log.warn("웹훅 이벤트 없음(스킵) - webhookEventIdx={}", message.webhookEventIdx());
            return;
        }

        // 멱등 ①: 이 레코드를 이미 처리했다(커밋 직전 종료 등으로 재소비된 경우)
        if (event.getStatus() == WebhookEventStatus.PROCESSED) {
            return;
        }

        String orderId = message.orderId();

        // 멱등 ②: 같은 주문을 다른 이벤트로 이미 처리했다(토스 재전송 등)
        boolean alreadyProcessed = orderId != null
                && webhookEventRepository.existsByOrderIdAndStatus(orderId, WebhookEventStatus.PROCESSED);

        if (!alreadyProcessed
                && EVENT_PAYMENT_STATUS_CHANGED.equals(message.eventType())
                && orderId != null) {
            if (STATUS_EXPIRED.equals(message.dataStatus())) {
                reservationTxService.expireReservation(orderId); // 멱등 ③: 내부 원자 UPDATE
            } else if (STATUS_CANCELED.equals(message.dataStatus())) {
                applyCancelIfPendingRefund(orderId);             // 환불 미확정(5xx) 잔류 건 보정
            }
        }

        event.markProcessed();
        webhookEventRepository.save(event);
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
