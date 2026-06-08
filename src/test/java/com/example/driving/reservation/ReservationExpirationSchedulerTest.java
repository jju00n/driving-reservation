package com.example.driving.reservation;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.scheduler.ReservationExpirationScheduler;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미결제 만료 스케줄러 통합 테스트 — reservedAt + 40분 초과 PAYMENT_PENDING 좀비 예약을
 * 만료 처리 + 재고 복구하고, 아직 시간이 안 된 건은 건드리지 않는지 검증한다.
 */
@DisplayName("미결제 만료 스케줄러 통합 테스트")
class ReservationExpirationSchedulerTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4;

    @Autowired
    private ReservationExpirationScheduler scheduler;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private ProgramRepository programRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private ReservationRepository reservationRepository;

    private Long memberIdx;
    private Long programIdx;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Member member = memberRepository.save(Member.builder()
                .email("expire-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000").role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now).build());
        this.memberIdx = member.getMemberIdx();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx()).name("M3").duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        this.programIdx = program.getProgramIdx();
    }

    private Long createSchedule() {
        LocalDateTime now = LocalDateTime.now();
        return scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(CAPACITY).remaining(REMAINING_AFTER_RESERVE).status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now).build()).getScheduleIdx();
    }

    private String createReservation(Long scheduleIdx, LocalDateTime reservedAt) {
        LocalDateTime now = LocalDateTime.now();
        String orderId = UUID.randomUUID().toString();
        reservationRepository.save(Reservation.builder()
                .memberIdx(memberIdx).scheduleIdx(scheduleIdx).programIdx(programIdx)
                .orderId(orderId).amount(AMOUNT).status(ReservationStatus.PAYMENT_PENDING)
                .reservedAt(reservedAt).createdAt(now).updatedAt(now).build());
        return orderId;
    }

    @Test
    @DisplayName("reservedAt 50분 전 PENDING - 만료 처리 + 재고 복구")
    void scheduler_expiresOverdueReservation() {
        Long scheduleIdx = createSchedule();
        String orderId = createReservation(scheduleIdx, LocalDateTime.now().minusMinutes(50));

        scheduler.expireOverduePendingReservations();

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY); // 복구됨
    }

    @Test
    @DisplayName("reservedAt 10분 전 PENDING - 아직 만료 대상 아님(건드리지 않음)")
    void scheduler_keepsRecentReservation() {
        Long scheduleIdx = createSchedule();
        String orderId = createReservation(scheduleIdx, LocalDateTime.now().minusMinutes(10));

        scheduler.expireOverduePendingReservations();

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE); // 복구 안 함
    }
}
