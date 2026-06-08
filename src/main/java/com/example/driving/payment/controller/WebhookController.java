package com.example.driving.payment.controller;

import com.example.driving.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토스 웹훅 수신 엔드포인트. 인증 없음(SecurityConfig 에서 permitAll).
 * 원문(raw JSON)을 그대로 받아 보관 후 처리하며, 토스에는 항상 200 으로 ack 한다.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void receive(@RequestBody String rawPayload) {
        webhookService.handle(rawPayload);
    }
}
