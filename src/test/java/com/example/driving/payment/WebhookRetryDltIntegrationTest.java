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
import com.example.driving.reservation.service.ReservationTxService;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 처리 실패 → 재시도 → DLT 격리 검증. 이번 구조 변경의 핵심이 여기 있다.
 *
 * <p>이전 동기 구조에서는 처리 중 예외가 나면 catch 로 삼키고 토스에 200 을 줬다. 토스는 재전송하지
 * 않고 만료 스케줄러는 PAYMENT_PENDING 예약만 훑으므로, 실패한 웹훅을 다시 집는 주체가 아무도
 * 없었다. 지금은 예외가 리스너 밖으로 나가면 오프셋이 커밋되지 않아 재소비되고, 백오프 재시도를
 * 소진하면 DLT 로 격리돼 흔적(FAILED)과 로그가 남는다.
 */
@DisplayName("웹훅 처리 실패 - 재시도 후 DLT 격리")
class WebhookRetryDltIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4;

    @Autowired
    private MockMvc mockMvc;
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

    /** 처리 단계를 강제로 실패시키기 위해 대체 — DB 순간 장애 등 일시 오류를 흉내낸다. */
    @MockitoBean
    private ReservationTxService reservationTxService;

    private String orderId;
    private Long scheduleIdx;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Member member = memberRepository.save(Member.builder()
                .email("dlt-" + UUID.randomUUID() + "@test.com").password("encoded")
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
        this.scheduleIdx = schedule.getScheduleIdx();

        this.orderId = UUID.randomUUID().toString();
        reservationRepository.save(Reservation.create(
                member.getMemberIdx(), scheduleIdx, program.getProgramIdx(), AMOUNT, orderId));
    }

    @Test
    @DisplayName("처리가 계속 실패하면 재시도 후 DLT 로 가고 FAILED 로 남는다 - 재고는 건드리지 않음")
    void processingFailure_retriesThenLandsInDlt() throws Exception {
        doThrow(new IllegalStateException("일시 장애 시뮬레이션"))
                .when(reservationTxService).expireReservation(anyString());

        String payload = """
                {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","status":"EXPIRED"}}
                """.formatted(orderId);

        // 수신 자체는 성공 — 처리가 실패해도 토스에는 200 이 나간다
        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        // DLT 리스너가 FAILED 로 마킹할 때까지 대기 (백오프 100ms → 3회 재시도)
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<WebhookEvent> events = webhookEventRepository.findByOrderId(orderId);
                    assertThat(events).hasSize(1);
                    assertThat(events.getFirst().getStatus()).isEqualTo(WebhookEventStatus.FAILED);
                });

        // 최초 1회 + 백오프 재시도 3회 = 4번 시도한 뒤에야 DLT 로 보낸다
        // (webhook.kafka.retry.attempts=3 → ExponentialBackOff.maxAttempts)
        verify(reservationTxService, times(4)).expireReservation(orderId);

        // 처리에 실패했으므로 예약/재고는 원상태 — 실패를 성공으로 덮지 않는다
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
    }
}
