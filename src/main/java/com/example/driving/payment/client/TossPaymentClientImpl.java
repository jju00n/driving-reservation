package com.example.driving.payment.client;

import com.example.driving.payment.client.dto.TossConfirmRequest;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.client.dto.TossErrorResponse;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TossPaymentClientImpl implements TossPaymentClient {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";

    private final RestClient tossRestClient;
    private final ObjectMapper objectMapper;

    public TossPaymentClientImpl(RestClient tossRestClient, ObjectMapper objectMapper) {
        this.tossRestClient = tossRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 토스 confirm 호출. 4xx 는 {@link TossPaymentException}(서킷 무시 대상)으로,
     * 그 외 5xx/IO/타임아웃은 그대로 전파되어 서킷브레이커가 집계 → 임계 초과 시 fallback 으로 빠진다.
     */
    @Override
    @CircuitBreaker(name = "tossPayments", fallbackMethod = "confirmFallback")
    public TossConfirmResponse confirm(TossConfirmRequest request) {
        return tossRestClient.post()
                .uri(CONFIRM_PATH)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    TossErrorResponse error = readError(res.getBody());
                    log.warn("토스 결제 거절 - orderId={}, code={}, message={}",
                            request.orderId(), error.code(), error.message());
                    throw new TossPaymentException(error.code(), error.message());
                })
                .body(TossConfirmResponse.class);
    }

    /**
     * 게이트웨이 장애(5xx/타임아웃) + 서킷 오픈 시 진입. 비즈니스 거절({@link TossPaymentException})은
     * {@code ignoreExceptions} 라 여기로 오지 않지만, 방어적으로 그대로 재전파한다.
     */
    @SuppressWarnings("unused")
    private TossConfirmResponse confirmFallback(TossConfirmRequest request, Throwable t) {
        if (t instanceof TossPaymentException e) {
            throw e;
        }
        log.error("토스 게이트웨이 장애 - orderId={}, cause={}", request.orderId(), t.toString());
        throw new PaymentGatewayUnavailableException(
                "결제 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", t);
    }

    private TossErrorResponse readError(java.io.InputStream body) {
        try {
            TossErrorResponse error = objectMapper.readValue(body, TossErrorResponse.class);
            if (error.code() == null && error.message() == null) {
                return new TossErrorResponse("UNKNOWN", "결제 승인에 실패했습니다.");
            }
            return error;
        } catch (Exception e) {
            return new TossErrorResponse("UNKNOWN", "결제 승인에 실패했습니다.");
        }
    }
}
