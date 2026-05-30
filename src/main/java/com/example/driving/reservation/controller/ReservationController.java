package com.example.driving.reservation.controller;

import com.example.driving.reservation.dto.CreateReservationRequest;
import com.example.driving.reservation.dto.CreateReservationResponse;
import com.example.driving.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateReservationResponse create(
            @AuthenticationPrincipal Long memberIdx,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        return reservationService.create(memberIdx, request.programIdx(), request.scheduleIdx());
    }
}
