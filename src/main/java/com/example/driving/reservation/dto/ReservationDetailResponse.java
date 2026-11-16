package com.example.driving.reservation.dto;

import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;

import java.time.LocalDateTime;

/**
 * 예약 상세 조회 응답. 결제 전(PAYMENT_PENDING) 예약은 결제행이 없을 수 있어 {@code paymentStatus} 가 null 이다.
 */
public record ReservationDetailResponse(
        Long reservationIdx,
        String orderId,
        ReservationStatus status,
        Long amount,
        LocalDateTime reservedAt,
        ProgramInfo program,
        ScheduleInfo schedule,
        PaymentStatus paymentStatus
) {
    public record ProgramInfo(String name, String vehicle, Integer duration) {
    }

    public record ScheduleInfo(LocalDateTime startAt, LocalDateTime endAt) {
    }

    public static ReservationDetailResponse of(
            Reservation reservation, Program program, Vehicle vehicle, Schedule schedule, Payment payment) {
        return new ReservationDetailResponse(
                reservation.getReservationIdx(),
                reservation.getOrderId(),
                reservation.getStatus(),
                reservation.getAmount(),
                reservation.getReservedAt(),
                new ProgramInfo(program.getName(), vehicle.getName() + " " + vehicle.getModel(), program.getDuration()),
                new ScheduleInfo(schedule.getStartAt(), schedule.getEndAt()),
                payment != null ? payment.getStatus() : null
        );
    }
}
