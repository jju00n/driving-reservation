package com.example.driving.payment.exception;

import lombok.Getter;

/**
 * 토스 결제 승인 API 의 <b>비즈니스 거절</b>(4xx, 예: 카드 한도/거절/만료).
 * 결과가 확정된 실패이므로 예약 실패 처리 + 재고 복구의 트리거가 된다.
 *
 * <p>서킷브레이커의 {@code ignoreExceptions} 로 등록해 카드 거절이 서킷을 열지 않도록 한다
 * (서킷은 5xx/타임아웃 등 게이트웨이 장애에만 반응해야 함).
 */
@Getter
public class TossPaymentException extends RuntimeException {

    private final String failureCode;

    public TossPaymentException(String failureCode, String failureMessage) {
        super(failureMessage);
        this.failureCode = failureCode;
    }
}
