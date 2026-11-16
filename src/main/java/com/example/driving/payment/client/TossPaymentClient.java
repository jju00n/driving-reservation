package com.example.driving.payment.client;

import com.example.driving.payment.client.dto.TossCancelRequest;
import com.example.driving.payment.client.dto.TossCancelResponse;
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

    /**
     * 결제 취소(전액 환불). 4xx 거절은 {@link com.example.driving.payment.exception.TossPaymentException}
     * (이때 {@code failureCode} 로 {@code ALREADY_CANCELED_PAYMENT}(이미 취소됨=멱등) 와 진짜 실패를 구분),
     * 5xx/타임아웃/서킷오픈은 {@link com.example.driving.payment.exception.PaymentGatewayUnavailableException} 로 던져진다.
     *
     * @param idempotencyKey 중복 취소 방지용 멱등키 헤더(같은 주문 재시도 시 동일 값을 보내면 토스가 1회만 처리).
     */
    TossCancelResponse cancel(String paymentKey, TossCancelRequest request, String idempotencyKey);
}
