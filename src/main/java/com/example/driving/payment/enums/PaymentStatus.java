package com.example.driving.payment.enums;

/**
 * 결제 상태.
 *
 * <p>결제 승인은 {@code REQUESTED → COMPLETED / FAILED}, 결제 취소(환불)는
 * {@code COMPLETED → REFUND_REQUESTED → REFUNDED} 를 사용한다.
 * 환불 실패(진짜 실패) 시에는 보상으로 {@code REFUND_REQUESTED → COMPLETED} 로 되돌린다.
 * {@code PENDING}(예약 시점 결제행 선생성)은 차후 확장을 위해 선언만 유지한다.
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

    public boolean isRefundRequested() {
        return this == REFUND_REQUESTED;
    }

    public boolean isRefunded() {
        return this == REFUNDED;
    }
}
