package com.example.driving.payment.repository;

import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import org.springframework.data.repository.CrudRepository;

public interface WebhookEventRepository extends CrudRepository<WebhookEvent, Long> {

    /** 같은 orderId 가 이미 처리 완료됐는지 — 웹훅 중복 수신 멱등 판단. */
    boolean existsByOrderIdAndStatus(String orderId, WebhookEventStatus status);
}
