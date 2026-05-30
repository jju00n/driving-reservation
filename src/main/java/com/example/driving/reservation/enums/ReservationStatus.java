package com.example.driving.reservation.enums;

public enum ReservationStatus {
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isPaymentPending() {
        return this == PAYMENT_PENDING;
    }

    public boolean isConfirmed() {
        return this == CONFIRMED;
    }
}
