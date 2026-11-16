package com.example.driving.reservation.dto;

import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;

/**
 * 예약 취소 결과. 결제 전(PAYMENT_PENDING) 취소는 결제행이 없을 수 있어 {@code paymentStatus} 가 null 이다.
 */
public record CancelReservationResponse(
        Long reservationIdx,
        String orderId,
        ReservationStatus reservationStatus,
        PaymentStatus paymentStatus
) {
    public static CancelReservationResponse of(Reservation reservation, Payment payment) {
        return new CancelReservationResponse(
                reservation.getReservationIdx(),
                reservation.getOrderId(),
                reservation.getStatus(),
                payment != null ? payment.getStatus() : null
        );
    }
}
