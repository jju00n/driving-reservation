package com.example.driving.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 토스페이먼츠 연동 설정. {@code toss.*} 프로퍼티 바인딩.
 */
@ConfigurationProperties(prefix = "toss")
public record TossProperties(
        String secretKey,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public TossProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(5);
        }
    }
}
