package com.example.driving.reservation.enums;

import java.util.List;

public enum ReservationStatus {
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED,
    CANCELLED,
    EXPIRED;

    /**
     * 재고를 점유 중인(= 중복 예약 방지 대상) 활성 상태.
     * 취소/만료/결제실패는 재시도 가능하므로 제외.
     */
    public static List<ReservationStatus> activeStatuses() {
        return List.of(PAYMENT_PENDING, CONFIRMED);
    }

    public boolean isPaymentPending() {
        return this == PAYMENT_PENDING;
    }

    public boolean isConfirmed() {
        return this == CONFIRMED;
    }
}
