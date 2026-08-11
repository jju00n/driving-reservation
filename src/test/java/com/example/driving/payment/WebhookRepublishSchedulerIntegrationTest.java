package com.example.driving.payment;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.domain.WebhookEvent;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.repository.WebhookEventRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 발행 유실 회수 검증(안전망 2차).
 *
 * <p>수신 단계는 "DB 저장 → Kafka 발행" 두 단계인데 한 트랜잭션으로 묶을 수 없다(외부 시스템).
 * 저장 직후 브로커가 죽거나 앱이 종료되면 이벤트가 RECEIVED 로 남은 채 아무도 처리하지 않는다.
 * 그 상황을 <b>발행 없이 RECEIVED 레코드만 만들어</b> 재현하고, 재발행 스케줄러가 주워서
 * 정상 처리(예약 만료 + 재고 복구)까지 도달하는지 본다.
 */
@DisplayName("웹훅 재발행 스케줄러 - 발행 유실분 회수")
@TestPropertySource(properties = {
        "webhook.republish.scan-interval-ms=500",
        "webhook.republish.stale-minutes=0"   // 저장 즉시 잔류로 판정(운영 기본 5분)
})
class WebhookRepublishSchedulerIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4;

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
    private WebhookEventRepository webhookEventRepository;

    @Test
    @DisplayName("RECEIVED 로 잔류한 이벤트를 재발행해 처리까지 끝낸다")
    void staleReceivedEvent_isRepublishedAndProcessed() {
        LocalDateTime now = LocalDateTime.now();
        Member member = memberRepository.save(Member.builder()
                .email("republish-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000").role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now).build());
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx()).name("M3").duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(program.getProgramIdx())
                .startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(CAPACITY).remaining(REMAINING_AFTER_RESERVE).status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now).build());
        Long scheduleIdx = schedule.getScheduleIdx();

        String orderId = UUID.randomUUID().toString();
        reservationRepository.save(Reservation.create(
                member.getMemberIdx(), scheduleIdx, program.getProgramIdx(), AMOUNT, orderId));

        // 발행에 실패한 상태 재현 — Kafka 로는 아무것도 보내지 않고 RECEIVED 레코드만 남긴다
        webhookEventRepository.save(WebhookEvent.builder()
                .eventType("PAYMENT_STATUS_CHANGED")
                .orderId(orderId)
                .rawPayload("""
                        {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","status":"EXPIRED"}}
                        """.formatted(orderId))
                .status(WebhookEventStatus.RECEIVED)
                .createdAt(now.minusMinutes(10))
                .build());

        // 스케줄러(0.5초 주기)가 재발행 → 컨슈머가 처리
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<WebhookEvent> events = webhookEventRepository.findByOrderId(orderId);
                    assertThat(events).hasSize(1);
                    assertThat(events.getFirst().getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
                });

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY); // 4 → 5, 유실될 뻔한 재고가 회수됨
    }
}
