package com.example.driving.payment.enums;

/**
 * 결제 상태.
 *
 * <p>1차(결제 승인) 범위에서는 {@code REQUESTED → COMPLETED / FAILED} 만 사용한다.
 * {@code PENDING}(예약 시점 결제행 선생성)과 {@code REFUND_REQUESTED/REFUNDED}(환불 플로우)는
 * 차후 확장을 위해 선언만 유지한다.
 */
public enum PaymentStatus {
    PENDING,
    REQUESTED,
    COMPLETED,
    REFUND_REQUESTED,
    REFUNDED,
    FAILED;

    public boolean isRequested() {
        return this == REQUESTED;
    }

    public boolean isCompleted() {
        return this == COMPLETED;
    }
}
