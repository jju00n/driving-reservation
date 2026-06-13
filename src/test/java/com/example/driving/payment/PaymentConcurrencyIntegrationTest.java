package com.example.driving.payment;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.repository.PaymentRepository;
import com.example.driving.payment.service.PaymentService;
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
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 결제 confirm 동시성 통합 테스트 — 같은 orderId 로 N개 스레드가 동시에 confirm 할 때
 * <b>토스 결제(외부호출)가 정확히 1회만</b> 일어나는지(이중 청구 방지) 검증한다.
 * 방어선: TX1 의 {@code uk_payments_order_id} 유니크 제약(동시 INSERT 1개만 성공) + 상태 멱등.
 *
 * 서비스를 직접 호출(HTTP 미경유)하고 토스 클라이언트는 Mock 으로 차단한다(예약 동시성 테스트와 동일 패턴).
 */
@DisplayName("결제 confirm 동시성 통합 테스트")
class PaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;

    @Autowired
    private PaymentService paymentService;
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

    @MockitoBean
    private TossPaymentClient tossPaymentClient;

    private Long memberIdx;
    private String orderId;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Member member = memberRepository.save(Member.builder()
                .email("pay-conc-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000").role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now).build());
        this.memberIdx = member.getMemberIdx();

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx()).name("M3").duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(program.getProgramIdx())
                .startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(5).remaining(4).status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now).build());

        this.orderId = UUID.randomUUID().toString();
        reservationRepository.save(Reservation.create(
                memberIdx, schedule.getScheduleIdx(), program.getProgramIdx(), AMOUNT, orderId));
    }

    @Test
    @DisplayName("같은 orderId 동시 confirm 5건 - 토스 결제는 정확히 1회, 예약 1건만 CONFIRMED")
    void confirm_concurrent_chargesTossOnce() throws InterruptedException {
        given(tossPaymentClient.confirm(any()))
                .willReturn(new TossConfirmResponse("pk_conc", orderId, "DONE", OffsetDateTime.now()));

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    paymentService.confirm(memberIdx, "pk_conc", orderId, AMOUNT);
                    okCount.incrementAndGet();
                } catch (com.example.driving.common.exception.BusinessException e) {
                    conflictCount.incrementAndGet(); // 동시 패자(409 "이미 진행 중") 등
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (unexpected.get() != null) {
            throw new AssertionError("의도치 않은 예외", unexpected.get());
        }
        assertThat(finished).isTrue();
        // ★ 핵심: 외부 결제는 단 1회만 발생(이중 청구 방지)
        verify(tossPaymentClient, times(1)).confirm(any());
        // 예약은 CONFIRMED, 결제행은 orderId 당 1건만
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
        assertThat(okCount.get()).as("성공(확정 or 멱등) 최소 1").isGreaterThanOrEqualTo(1);
        assertThat(okCount.get() + conflictCount.get()).isEqualTo(threadCount);
    }
}
