package com.example.driving.reservation;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.payment.repository.PaymentHistoryRepository;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.dto.CreateReservationResponse;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.service.ReservationService;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("예약 신청 통합 테스트 - 분산락 동시성")
class ReservationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReservationService reservationService;

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
    private ReservationHistoryRepository reservationHistoryRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    private Long programIdx;

    @BeforeEach
    void cleanup() {
        // 공유 컨테이너 — 다른 통합테스트(결제)가 남긴 payments 가 reservations 를 FK 참조하므로 먼저 삭제(자식→부모 순).
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        reservationHistoryRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        programRepository.deleteAll();
        vehicleRepository.deleteAll();
        memberRepository.deleteAll();

        LocalDateTime now = LocalDateTime.now();
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
    }

    private Long createSchedule(int capacity, int remaining, ScheduleStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(now.plusDays(1))
                .endAt(now.plusDays(1).plusHours(1))
                .capacity(capacity).remaining(remaining)
                .status(status)
                .createdAt(now).updatedAt(now)
                .build()).getScheduleIdx();
    }

    private List<Long> createMembers(int count) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Member m = memberRepository.save(Member.builder()
                    .email("user" + i + "@test.com")
                    .password("encoded")
                    .name("user" + i)
                    .phone("01000000000")
                    .role(Role.CUSTOMER)
                    .createdAt(now).updatedAt(now)
                    .build());
            ids.add(m.getMemberIdx());
        }
        return ids;
    }

    @Test
    @DisplayName("단일 요청 - 정상 예약, 재고 1 차감, History 1건 생성")
    void create_single_success() {
        Long scheduleIdx = createSchedule(5, 5, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        CreateReservationResponse response = reservationService.create(memberIdx, programIdx, scheduleIdx);

        assertThat(response.reservationIdx()).isNotNull();
        assertThat(response.orderId()).isNotBlank();
        assertThat(response.amount()).isEqualTo(150_000L);

        Schedule after = scheduleRepository.findById(scheduleIdx).orElseThrow();
        assertThat(after.getRemaining()).isEqualTo(4);

        Reservation saved = reservationRepository.findByOrderId(response.orderId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(reservationHistoryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 10명이 capacity=5 스케줄에 예약 시도 - 정확히 5명만 성공")
    void create_concurrent_capacity_limit() throws InterruptedException {
        int capacity = 5;
        int threadCount = 10;

        Long scheduleIdx = createSchedule(capacity, capacity, ScheduleStatus.OPEN);
        List<Long> members = createMembers(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            final Long memberIdx = members.get(i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    reservationService.create(memberIdx, programIdx, scheduleIdx);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedError.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (unexpectedError.get() != null) {
            throw new AssertionError("의도치 않은 예외 발생", unexpectedError.get());
        }
        assertThat(finished).as("모든 스레드가 30초 안에 종료되어야 함").isTrue();
        assertThat(successCount.get()).as("정원만큼만 성공").isEqualTo(capacity);
        assertThat(failCount.get()).as("초과 요청은 모두 실패").isEqualTo(threadCount - capacity);

        Schedule after = scheduleRepository.findById(scheduleIdx).orElseThrow();
        assertThat(after.getRemaining()).as("재고가 정확히 0").isEqualTo(0);

        assertThat(reservationRepository.count()).as("저장된 예약 수 = capacity").isEqualTo(capacity);
        assertThat(reservationHistoryRepository.count()).as("히스토리도 capacity만큼").isEqualTo(capacity);
    }

    @Test
    @DisplayName("동시 요청이 capacity와 같으면 - 전원 성공, 재고 0")
    void create_concurrent_exact_capacity() throws InterruptedException {
        int capacity = 5;
        Long scheduleIdx = createSchedule(capacity, capacity, ScheduleStatus.OPEN);
        List<Long> members = createMembers(capacity);

        ExecutorService executor = Executors.newFixedThreadPool(capacity);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(capacity);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        for (int i = 0; i < capacity; i++) {
            final Long memberIdx = members.get(i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    reservationService.create(memberIdx, programIdx, scheduleIdx);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedError.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (unexpectedError.get() != null) {
            throw new AssertionError("의도치 않은 예외 발생", unexpectedError.get());
        }
        assertThat(successCount.get()).isEqualTo(capacity);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("스케줄 CLOSED - 예약 불가")
    void create_fail_closedSchedule() {
        Long scheduleIdx = createSchedule(5, 5, ScheduleStatus.CLOSED);
        Long memberIdx = createMembers(1).get(0);

        assertThatThrownBy(() -> reservationService.create(memberIdx, programIdx, scheduleIdx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("예약 가능한 스케줄이 아닙니다");

        assertThat(reservationRepository.count()).isEqualTo(0);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고가 0이면 - 예약 불가")
    void create_fail_noRemaining() {
        Long scheduleIdx = createSchedule(5, 0, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        assertThatThrownBy(() -> reservationService.create(memberIdx, programIdx, scheduleIdx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잔여석이 없습니다");

        assertThat(reservationRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("같은 회원이 같은 스케줄에 중복 예약 - 두 번째 실패, 재고 1만 차감")
    void create_fail_duplicateReservation() {
        Long scheduleIdx = createSchedule(2, 2, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        reservationService.create(memberIdx, programIdx, scheduleIdx);

        assertThatThrownBy(() -> reservationService.create(memberIdx, programIdx, scheduleIdx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 해당 스케줄에 예약이 있습니다");

        assertThat(reservationRepository.count()).as("예약은 1건만").isEqualTo(1);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .as("재고는 1만 차감").isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 같은 회원이 capacity=5 스케줄에 5번 예약 시도 - 1명만 성공")
    void create_concurrent_sameMember_onlyOne() throws InterruptedException {
        int threadCount = 5;
        Long scheduleIdx = createSchedule(threadCount, threadCount, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    reservationService.create(memberIdx, programIdx, scheduleIdx);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedError.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (unexpectedError.get() != null) {
            throw new AssertionError("의도치 않은 예외 발생", unexpectedError.get());
        }
        assertThat(successCount.get()).as("한 회원은 1번만 성공").isEqualTo(1);
        assertThat(failCount.get()).as("나머지는 중복으로 실패").isEqualTo(threadCount - 1);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .as("재고는 1만 차감").isEqualTo(threadCount - 1);
    }

    // --- DB 제약(uk_reservations_active_member_schedule) 직접 검증 ---
    // 위 테스트들은 분산락+사전조회(앱 레벨)를 검증한다. 아래는 그 우회 시 최종 방어선인 DB 제약 자체를
    // repository.save 로 직접 때려서 검증한다. 활성=키 / 비활성=NULL 생성 컬럼의 동작을 증명.

    private Reservation activeReservation(Long memberIdx, Long scheduleIdx, String orderId) {
        LocalDateTime now = LocalDateTime.now();
        return Reservation.builder()
                .memberIdx(memberIdx).scheduleIdx(scheduleIdx).programIdx(programIdx)
                .orderId(orderId).amount(150_000L)
                .status(ReservationStatus.PAYMENT_PENDING)
                .reservedAt(now).createdAt(now).updatedAt(now)
                .build();
    }

    @Test
    @DisplayName("DB 제약 - 같은 회원+스케줄 활성 예약 2건 저장 시 두 번째 거부")
    void dbConstraint_rejectsDuplicateActive() {
        Long scheduleIdx = createSchedule(5, 5, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        reservationRepository.save(activeReservation(memberIdx, scheduleIdx, "order-1"));

        assertThatThrownBy(() ->
                reservationRepository.save(activeReservation(memberIdx, scheduleIdx, "order-2")))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_reservations_active_member_schedule");

        assertThat(reservationRepository.count()).as("저장된 예약은 1건뿐").isEqualTo(1);
    }

    @Test
    @DisplayName("DB 제약 - 취소(비활성)로 바뀌면 같은 회원+스케줄 재예약 허용")
    void dbConstraint_allowsReReservationAfterInactive() {
        Long scheduleIdx = createSchedule(5, 5, ScheduleStatus.OPEN);
        Long memberIdx = createMembers(1).get(0);

        Reservation first = reservationRepository.save(activeReservation(memberIdx, scheduleIdx, "order-1"));
        first.cancel(); // PAYMENT_PENDING → CANCELLED, active_dup_key 가 NULL 로 바뀜
        reservationRepository.save(first);

        Reservation second = reservationRepository.save(activeReservation(memberIdx, scheduleIdx, "order-2"));

        assertThat(second.getReservationIdx()).as("비활성 해제 후 재예약 성공").isNotNull();
        assertThat(reservationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("DB 제약 - 다른 회원은 같은 스케줄에 각자 활성 예약 가능")
    void dbConstraint_allowsDifferentMembersSameSchedule() {
        Long scheduleIdx = createSchedule(5, 5, ScheduleStatus.OPEN);
        List<Long> members = createMembers(2);

        reservationRepository.save(activeReservation(members.get(0), scheduleIdx, "order-1"));
        Reservation other = reservationRepository.save(activeReservation(members.get(1), scheduleIdx, "order-2"));

        assertThat(other.getReservationIdx()).isNotNull();
        assertThat(reservationRepository.count()).isEqualTo(2);
    }
}
