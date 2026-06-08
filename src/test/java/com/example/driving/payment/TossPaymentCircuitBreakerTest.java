package com.example.driving.payment;

import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmRequest;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.support.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토스 결제 서킷브레이커 통합 테스트 — WireMock(HTTP 목서버)로 실제 RestClient → @CircuitBreaker 경로를 검증한다.
 * (@MockitoBean 으로 클라이언트 인터페이스를 통째 교체하는 컨트롤러 테스트와 달리, 여기선 실제 구현 빈이 돈다.)
 *
 * 검증: ① 5xx 반복 시 서킷 OPEN → 이후 외부호출 자체가 차단(fallback 직행)
 *      ② 4xx(카드 거절)는 ignore-exceptions 라 서킷이 열리지 않음(CLOSED 유지)
 */
@DisplayName("토스 결제 서킷브레이커 통합 테스트 (WireMock)")
class TossPaymentCircuitBreakerTest extends AbstractIntegrationTest {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    @Autowired
    private TossPaymentClient tossPaymentClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void tossProperties(DynamicPropertyRegistry registry) {
        if (!WIRE_MOCK.isRunning()) {
            WIRE_MOCK.start();
        }
        registry.add("toss.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("tossPayments").reset();
    }

    private TossConfirmRequest request() {
        return new TossConfirmRequest("pk_cb", "order-cb", 150_000L);
    }

    @Test
    @DisplayName("5xx 반복 - 임계 초과 시 서킷 OPEN, 이후 외부호출 차단(fallback 직행)")
    void circuit_opens_on_5xx() {
        WIRE_MOCK.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(500)));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("tossPayments");

        // minimum-number-of-calls=5, failure-rate-threshold=50 → 5번 5xx면 OPEN
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> tossPaymentClient.confirm(request()))
                    .isInstanceOf(PaymentGatewayUnavailableException.class);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태에선 토스를 호출하지 않고 fallback 으로 직행
        WIRE_MOCK.resetRequests();
        assertThatThrownBy(() -> tossPaymentClient.confirm(request()))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("4xx 거절 반복 - 서킷 CLOSED 유지(ignore-exceptions)")
    void circuit_stays_closed_on_4xx() {
        WIRE_MOCK.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"한도 초과\"}")));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("tossPayments");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> tossPaymentClient.confirm(request()))
                    .isInstanceOf(TossPaymentException.class);
        }
        // 카드 거절(4xx)은 게이트웨이 장애가 아니므로 서킷 집계에서 제외 → 닫힌 상태 유지
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
