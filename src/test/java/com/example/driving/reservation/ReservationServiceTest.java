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
import com.example.driving.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @InjectMocks
    private ReservationService reservationService;

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RLock rLock;
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

    @BeforeEach
    void setUp() {
        given(redissonClient.getLock(anyString())).willReturn(rLock);
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplateExecutesCallback() {
        given(transactionTemplate.execute(any(TransactionCallback.class)))
                .willAnswer(invocation -> {
                    TransactionCallback<Object> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

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
    void create_success() throws InterruptedException {
        Schedule schedule = openSchedule(5);
        Program program = activeProgram();

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));
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

        CreateReservationResponse response = reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX);

        assertThat(response.reservationIdx()).isEqualTo(999L);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.orderId()).isNotBlank();
        assertThat(schedule.getRemaining()).isEqualTo(4);
        verify(scheduleRepository).save(schedule);
        verify(reservationRepository).save(any(Reservation.class));
        verify(reservationHistoryRepository).save(any(ReservationHistory.class));
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 - CONFLICT 예외, DB 호출 없음")
    void create_fail_lockNotAcquired() throws InterruptedException {
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잠시 후 다시 시도")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(scheduleRepository, never()).findById(anyLong());
        verify(reservationRepository, never()).save(any());
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("스케줄 없음 - NOT_FOUND")
    void create_fail_scheduleNotFound() throws InterruptedException {
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("스케줄을 찾을 수 없습니다");

        verify(rLock).unlock();
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("스케줄 CLOSED - 예약 불가")
    void create_fail_scheduleClosed() throws InterruptedException {
        Schedule schedule = Schedule.builder()
                .scheduleIdx(SCHEDULE_IDX)
                .programIdx(PROGRAM_IDX)
                .capacity(10).remaining(5)
                .status(ScheduleStatus.CLOSED)
                .startAt(LocalDateTime.now()).endAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("예약 가능한 스케줄이 아닙니다");

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("잔여석 0 - 도메인이 예외 (스케줄 OPEN이어도)")
    void create_fail_noRemaining() throws InterruptedException {
        Schedule schedule = openSchedule(0);
        Program program = activeProgram();

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잔여석이 없습니다");

        verify(scheduleRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("programIdx 불일치 - 스케줄과 프로그램이 매칭되지 않으면 예외")
    void create_fail_programMismatch() throws InterruptedException {
        Schedule schedule = openSchedule(5);

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));

        Long wrongProgramIdx = 9999L;

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, wrongProgramIdx, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("스케줄과 프로그램이 일치하지 않습니다");

        verify(programRepository, never()).findById(anyLong());
        verify(scheduleRepository, never()).save(any());
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("프로그램 INACTIVE - 종료된 프로그램")
    void create_fail_programInactive() throws InterruptedException {
        Schedule schedule = openSchedule(5);
        Program program = Program.builder()
                .programIdx(PROGRAM_IDX).vehicleIdx(1L)
                .name("X").duration(60).amount(AMOUNT)
                .status(ProgramStatus.INACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        stubTransactionTemplateExecutesCallback();
        given(scheduleRepository.findById(SCHEDULE_IDX)).willReturn(Optional.of(schedule));
        given(programRepository.findById(PROGRAM_IDX)).willReturn(Optional.of(program));

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("종료된 프로그램입니다");

        verify(scheduleRepository, never()).save(any());
        verify(rLock).unlock();
    }
}
