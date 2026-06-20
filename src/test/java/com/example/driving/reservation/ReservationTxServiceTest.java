package com.example.driving.reservation;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.domain.ReservationHistory;
import com.example.driving.reservation.dto.CreateReservationResponse;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.service.ReservationTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 예약 신청/만료의 트랜잭션 경계 빈 단위 테스트.
 *
 * <p>검증/재고차감/저장/만료 등 DB 작업 로직은 {@link ReservationTxService} 의 책임이므로 여기서 검증한다.
 * 단위 테스트에서는 프록시 없이 메서드를 직접 호출하므로 {@code @Transactional} 의 트랜잭션 동작은
 * 검증 대상이 아니다(통합 테스트가 담당).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("예약 트랜잭션 빈 단위 테스트")
class ReservationTxServiceTest {

    @InjectMocks
    private ReservationTxService reservationTxService;

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ProgramRepository programRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationHistoryRepository reservationHistoryRepository;

    private static final Long MEMBER_IDX = 100L;
    private static final Long SCHEDULE_IDX = 10L;
    private static final Long PROGRAM_IDX = 1L;
    private static final Long AMOUNT = 50_000L;

    private Schedule openSchedule(int remaining) {
        LocalDateTime now = LocalDateTime.now();
        return Schedule.builder()
                .scheduleIdx(SCHEDULE_IDX)
                .programIdx(PROGRAM_IDX)
                .startAt(now.plusDays(1))
                .endAt(now.plusDays(1).plusHours(1))
                .capacity(10)
                .remaining(remaining)
                .status(ScheduleStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Program activeProgram() {
        LocalDateTime now = LocalDateTime.now();
        return Program.builder()
                .programIdx(PROGRAM_IDX)
                .vehicleIdx(1L)
                .name("M3 시승")
                .duration(60)
                .amount(AMOUNT)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    @DisplayName("예약 신청 성공 - 재고 차감/Reservation/History 저장이 모두 호출된다")
    void create_success() {
        Schedule schedule = openSchedule(5);
        Program program = activeProgram();

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));
        given(scheduleRepository.decreaseRemainingIfAvailable(SCHEDULE_IDX)).willReturn(1);
        given(reservationRepository.save(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation r = invocation.getArgument(0);
                    return Reservation.builder()
                            .reservationIdx(999L)
                            .memberIdx(r.getMemberIdx())
                            .scheduleIdx(r.getScheduleIdx())
                            .programIdx(r.getProgramIdx())
                            .orderId(r.getOrderId())
                            .amount(r.getAmount())
                            .status(r.getStatus())
                            .reservedAt(r.getReservedAt())
                            .createdAt(r.getCreatedAt())
                            .updatedAt(r.getUpdatedAt())
                            .build();
                });

        CreateReservationResponse response =
                reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX);

        assertThat(response.reservationIdx()).isEqualTo(999L);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.orderId()).isNotBlank();
        verify(scheduleRepository).decreaseRemainingIfAvailable(SCHEDULE_IDX);
        verify(reservationRepository).save(any(Reservation.class));
        verify(reservationHistoryRepository).save(any(ReservationHistory.class));
    }

    @Test
    @DisplayName("스케줄 없음 - NOT_FOUND")
    void create_fail_scheduleNotFound() {
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("스케줄을 찾을 수 없습니다");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("스케줄 CLOSED - 예약 불가")
    void create_fail_scheduleClosed() {
        Schedule schedule = Schedule.builder()
                .scheduleIdx(SCHEDULE_IDX)
                .programIdx(PROGRAM_IDX)
                .capacity(10).remaining(5)
                .status(ScheduleStatus.CLOSED)
                .startAt(LocalDateTime.now()).endAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("예약 가능한 스케줄이 아닙니다");
    }

    @Test
    @DisplayName("잔여석 0 - 원자 차감 UPDATE가 0행이면 예외 (스케줄 OPEN이어도)")
    void create_fail_noRemaining() {
        Schedule schedule = openSchedule(0);
        Program program = activeProgram();

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));
        given(scheduleRepository.decreaseRemainingIfAvailable(SCHEDULE_IDX)).willReturn(0);

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잔여석이 없습니다");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("programIdx 불일치 - 스케줄과 프로그램이 매칭되지 않으면 예외")
    void create_fail_programMismatch() {
        Schedule schedule = openSchedule(5);

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));

        Long wrongProgramIdx = 9999L;

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, wrongProgramIdx, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("스케줄과 프로그램이 일치하지 않습니다");

        verify(programRepository, never()).findById(anyLong());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("프로그램 INACTIVE - 종료된 프로그램")
    void create_fail_programInactive() {
        Schedule schedule = openSchedule(5);
        Program program = Program.builder()
                .programIdx(PROGRAM_IDX).vehicleIdx(1L)
                .name("X").duration(60).amount(AMOUNT)
                .status(ProgramStatus.INACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("종료된 프로그램입니다");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("중복 예약 - 활성 상태 예약이 이미 있으면 CONFLICT")
    void create_fail_duplicate() {
        Schedule schedule = openSchedule(5);
        Program program = activeProgram();

        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));
        given(reservationRepository.existsByMemberIdxAndScheduleIdxAndStatusIn(
                MEMBER_IDX, SCHEDULE_IDX, ReservationStatus.activeStatuses())).willReturn(true);

        assertThatThrownBy(() -> reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 해당 스케줄에 예약이 있습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(scheduleRepository, never()).decreaseRemainingIfAvailable(anyLong());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료 성공 - PENDING이면 만료 처리 + 재고 복구 + 이력 저장")
    void expire_success() {
        String orderId = "order-expire";
        Reservation reservation = Reservation.builder()
                .reservationIdx(7L).memberIdx(MEMBER_IDX).scheduleIdx(SCHEDULE_IDX).programIdx(PROGRAM_IDX)
                .orderId(orderId).amount(AMOUNT).status(ReservationStatus.PAYMENT_PENDING)
                .reservedAt(LocalDateTime.now().minusMinutes(50))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        given(reservationRepository.findByOrderId(orderId)).willReturn(Optional.of(reservation));
        given(reservationRepository.expireIfPending(orderId)).willReturn(1);

        reservationTxService.expireReservation(orderId);

        verify(scheduleRepository).increaseRemaining(SCHEDULE_IDX);
        verify(reservationHistoryRepository).save(any(ReservationHistory.class));
    }

    @Test
    @DisplayName("만료 멱등 - 이미 처리됨(expireIfPending=0)이면 재고 복구하지 않음")
    void expire_idempotent_noRecovery() {
        String orderId = "order-expire";
        Reservation reservation = Reservation.builder()
                .reservationIdx(7L).memberIdx(MEMBER_IDX).scheduleIdx(SCHEDULE_IDX).programIdx(PROGRAM_IDX)
                .orderId(orderId).amount(AMOUNT).status(ReservationStatus.PAYMENT_PENDING)
                .reservedAt(LocalDateTime.now().minusMinutes(50))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        given(reservationRepository.findByOrderId(orderId)).willReturn(Optional.of(reservation));
        given(reservationRepository.expireIfPending(orderId)).willReturn(0);

        reservationTxService.expireReservation(orderId);

        verify(scheduleRepository, never()).increaseRemaining(anyLong());
        verify(reservationHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료 대상 없음 - 예약이 없으면 아무 작업도 하지 않음")
    void expire_notFound() {
        String orderId = "order-missing";
        given(reservationRepository.findByOrderId(orderId)).willReturn(Optional.empty());

        reservationTxService.expireReservation(orderId);

        verify(reservationRepository, never()).expireIfPending(any());
        verify(scheduleRepository, never()).increaseRemaining(anyLong());
    }
}
