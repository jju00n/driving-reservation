package com.example.driving.reservation.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.dto.ReservationDetailResponse;
import com.example.driving.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 조회(읽기 전용) 오케스트레이션. Spring Data JDBC 는 연관을 자동 로딩하지 않으므로
 * 프로그램/차량/스케줄/결제를 각각 조회해 {@link ReservationDetailResponse} 로 조립한다.
 *
 * <p>소유권 검증(본인 예약만 조회)은 취소({@link ReservationCancelService})와 동일 정책 —
 * 다른 회원 예약은 403.
 */
@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;
    private final ProgramRepository programRepository;
    private final VehicleRepository vehicleRepository;
    private final ScheduleRepository scheduleRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long memberIdx, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException("예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!reservation.getMemberIdx().equals(memberIdx)) {
            throw new BusinessException("본인의 예약만 조회할 수 있습니다.", HttpStatus.FORBIDDEN);
        }

        Program program = programRepository.findById(reservation.getProgramIdx())
                .orElseThrow(() -> new BusinessException("프로그램을 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        Vehicle vehicle = vehicleRepository.findById(program.getVehicleIdx())
                .orElseThrow(() -> new BusinessException("차량을 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        Schedule schedule = scheduleRepository.findById(reservation.getScheduleIdx())
                .orElseThrow(() -> new BusinessException("스케줄을 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR));
        Payment payment = paymentRepository.findByOrderId(reservation.getOrderId()).orElse(null);

        return ReservationDetailResponse.of(reservation, program, vehicle, schedule, payment);
    }
}
