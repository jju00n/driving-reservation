package com.example.driving.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record CreateReservationRequest(
        @NotNull(message = "programIdx는 필수입니다.")
        Long programIdx,
        @NotNull(message = "scheduleIdx는 필수입니다.")
        Long scheduleIdx
) {
}
