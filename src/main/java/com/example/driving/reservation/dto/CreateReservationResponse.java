package com.example.driving.reservation.dto;

import com.example.driving.reservation.domain.Reservation;

public record CreateReservationResponse(
        Long reservationIdx,
        String orderId,
        Long amount
) {
    public static CreateReservationResponse from(Reservation reservation) {
        return new CreateReservationResponse(
                reservation.getReservationIdx(),
                reservation.getOrderId(),
                reservation.getAmount()
        );
    }
}
