package com.example.driving.payment.dto;

import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentConfirmResponse(
        String orderId,
        PaymentStatus status,
        LocalDateTime approvedAt
) {
    public static PaymentConfirmResponse from(Payment payment) {
        return new PaymentConfirmResponse(
                payment.getOrderId(),
                payment.getStatus(),
                payment.getApprovedAt()
        );
    }
}
