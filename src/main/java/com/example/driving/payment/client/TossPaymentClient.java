package com.example.driving.payment.client;

import com.example.driving.payment.client.dto.TossConfirmRequest;
import com.example.driving.payment.client.dto.TossConfirmResponse;

/**
 * 토스페이먼츠 외부 연동 경계. 테스트에서는 이 인터페이스를 Mock 으로 대체해 네트워크를 차단한다.
 */
public interface TossPaymentClient {

    /**
     * 결제 승인. 비즈니스 거절(4xx)은 {@link com.example.driving.payment.exception.TossPaymentException},
     * 게이트웨이 장애(5xx/타임아웃/서킷오픈)는
     * {@link com.example.driving.payment.exception.PaymentGatewayUnavailableException} 로 변환되어 던져진다.
     */
    TossConfirmResponse confirm(TossConfirmRequest request);
}
