package com.example.driving.reservation;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.service.ReservationTxService;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 예약 신청 트랜잭션 롤백 검증 통합 테스트.
 *
 * 단위 테스트(ReservationTxServiceTest)는 @Transactional 동작을 검증할 수 없다 —
 * 프록시 없이 직접 호출하므로 트랜잭션이 걸리지 않기 때문이다.
 * 이 테스트는 실제 DB(Testcontainers)에서 이력 저장 중 예외가 터졌을 때
 * 재고 차감과 예약 저장이 함께 롤백되는지를 검증한다.
 */
@DisplayName("예약 트랜잭션 롤백 통합 테스트")
class ReservationTxRollbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReservationTxService reservationTxService;

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
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @MockitoSpyBean
    private ReservationHistoryRepository reservationHistoryRepository;

    private Long memberIdx;
    private Long programIdx;
    private Long scheduleIdx;

    @BeforeEach
    void setup() {
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        reservationHistoryRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        programRepository.deleteAll();
        vehicleRepository.deleteAll();
        memberRepository.deleteAll();

        LocalDateTime now = LocalDateTime.now();

        Member member = memberRepository.save(Member.builder()
                .email("rollback-test@test.com")
                .password("encoded")
                .name("테스터")
                .phone("01000000000")
                .role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now)
                .build());
        this.memberIdx = member.getMemberIdx();

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());

        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("M3 드라이빙 체험")
                .duration(60).amount(150_000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        this.programIdx = program.getProgramIdx();

        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(now.plusDays(1))
                .endAt(now.plusDays(1).plusHours(1))
                .capacity(5).remaining(5)
                .status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now)
                .build());
        this.scheduleIdx = schedule.getScheduleIdx();
    }

    @Test
    @DisplayName("이력 저장 중 예외 발생 시 - 재고 차감과 예약 저장이 함께 롤백된다")
    void createReservation_rollback_whenHistorySaveFails() {
        doThrow(new RuntimeException("이력 저장 강제 실패"))
                .when(reservationHistoryRepository).save(any());

        assertThatThrownBy(() ->
                reservationTxService.createReservation(memberIdx, programIdx, scheduleIdx))
                .isInstanceOf(RuntimeException.class);

        Schedule after = scheduleRepository.findById(scheduleIdx).orElseThrow();
        assertThat(after.getRemaining())
                .as("재고 차감이 롤백되어 원래대로 복구되어야 한다")
                .isEqualTo(5);

        assertThat(reservationRepository.count())
                .as("예약이 롤백되어 저장되지 않아야 한다")
                .isEqualTo(0);
    }
}
