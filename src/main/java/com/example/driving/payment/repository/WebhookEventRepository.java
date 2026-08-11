package com.example.driving.payment.repository;

import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import org.springframework.data.repository.CrudRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookEventRepository extends CrudRepository<WebhookEvent, Long> {

    /** 같은 orderId 가 이미 처리 완료됐는지 — 웹훅 중복 수신 멱등 판단. */
    boolean existsByOrderIdAndStatus(String orderId, WebhookEventStatus status);

    /**
     * 발행 실패로 RECEIVED 에 남은 좀비 이벤트 조회(재발행 대상).
     * 정상 건은 컨슈머가 초 단위로 PROCESSED 로 넘기므로, 임계 시간을 넘긴 RECEIVED = 발행 유실로 본다.
     */
    List<WebhookEvent> findByStatusAndCreatedAtBefore(WebhookEventStatus status, LocalDateTime threshold);

    /** 주문별 수신 이력 — 감사 조회 및 처리 완료 판정용. */
    List<WebhookEvent> findByOrderId(String orderId);
}
