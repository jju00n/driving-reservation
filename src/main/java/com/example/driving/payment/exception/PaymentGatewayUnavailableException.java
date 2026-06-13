package com.example.driving.payment.exception;

/**
 * 토스 게이트웨이 장애(5xx/타임아웃/서킷 오픈)로 결제 결과가 <b>미확정(UNKNOWN)</b>인 상황.
 *
 * <p>이때는 예약/결제 상태를 그대로(PAYMENT_PENDING / REQUESTED) 두고 503 으로 재시도를 유도한다.
 * 재고 복구를 하지 않는다 — 토스에서 실제 승인됐는데 응답만 유실됐을 수 있어, 좌석을 풀면
 * 중복 판매 위험이 있기 때문. 미확정 갭은 2차(웹훅/만료 스케줄러)가 보정한다.
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
