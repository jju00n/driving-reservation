package com.example.driving.payment;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.enums.WebhookEventStatus;
import com.example.driving.payment.repository.PaymentRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토스 웹훅 수신 통합 테스트 — PAYMENT_STATUS_CHANGED(EXPIRED) 수신 시 예약 만료 + 재고 복구,
 * 중복 수신 멱등, 인증 불필요(permitAll)를 검증한다.
 */
@DisplayName("토스 웹훅 수신 통합 테스트")
class WebhookControllerIntegrationTest extends AbstractIntegrationTest {

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
    private PaymentRepository paymentRepository;
    @Autowired
    private WebhookEventRepository webhookEventRepository;

    private String orderId;
    private Long scheduleIdx;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Member member = memberRepository.save(Member.builder()
                .email("webhook-" + UUID.randomUUID() + "@test.com").password("encoded")
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

    private String expiredPayload(String orderId) {
        return """
                {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","status":"EXPIRED"}}
                """.formatted(orderId);
    }

    private String canceledPayload(String orderId) {
        return """
                {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","status":"CANCELED"}}
                """.formatted(orderId);
    }

    /** setUp 의 PENDING 예약을 "환불 미확정(5xx) 잔류" 상태(CONFIRMED + 결제 REFUND_REQUESTED)로 만든다. */
    private void makeRefundRequested() {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        reservation.confirm();
        reservationRepository.save(reservation);

        Payment payment = Payment.request(reservation.getReservationIdx(), orderId, AMOUNT, "pk_" + orderId);
        payment.complete(LocalDateTime.now());
        payment.refundRequest(); // COMPLETED → REFUND_REQUESTED (cancel 호출 직전 상태)
        paymentRepository.save(payment);
    }

    /** 정상 결제 완료(CONFIRMED + 결제 COMPLETED) — 환불 요청한 적 없는 비대상 상태. */
    private void makeConfirmedCompleted() {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        reservation.confirm();
        reservationRepository.save(reservation);

        Payment payment = Payment.request(reservation.getReservationIdx(), orderId, AMOUNT, "pk_" + orderId);
        payment.complete(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    @Test
    @DisplayName("EXPIRED 웹훅 - 인증 없이 200 + 예약 EXPIRED + 재고 복구")
    void webhook_expired_expiresReservationAndRestoresStock() throws Exception {
        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expiredPayload(orderId)))
                .andExpect(status().isOk());

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY); // 4 → 5
        assertThat(webhookEventRepository.existsByOrderIdAndStatus(orderId, WebhookEventStatus.PROCESSED))
                .isTrue();
    }

    @Test
    @DisplayName("중복 수신 - 재고는 한 번만 복구(멱등)")
    void webhook_duplicate_restoresStockOnce() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/payments/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(expiredPayload(orderId)))
                    .andExpect(status().isOk());
        }
        // 3번 수신해도 재고는 capacity 까지만(중복 복구 없음)
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY);
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("EXPIRED 아닌 상태(DONE) - 예약/재고 변화 없음")
    void webhook_nonExpired_noChange() throws Exception {
        String donePayload = """
                {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","status":"DONE"}}
                """.formatted(orderId);

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(donePayload))
                .andExpect(status().isOk());

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
    }

    @Test
    @DisplayName("CANCELED 웹훅 - REFUND_REQUESTED 잔류 건 → 예약 CANCELLED + 결제 REFUNDED + 재고 복구")
    void webhook_canceled_finalizesPendingRefund() throws Exception {
        makeRefundRequested();

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canceledPayload(orderId)))
                .andExpect(status().isOk());

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY); // 4 → 5
    }

    @Test
    @DisplayName("CANCELED 중복 수신 - 재고는 한 번만 복구(멱등)")
    void webhook_canceled_duplicate_restoresStockOnce() throws Exception {
        makeRefundRequested();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/payments/webhook")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(canceledPayload(orderId)))
                    .andExpect(status().isOk());
        }

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY); // 중복 복구 없음
    }

    @Test
    @DisplayName("CANCELED 웹훅 - 비대상(REFUND_REQUESTED 아님, 정상 CONFIRMED) → 변화 없음")
    void webhook_canceled_nonTarget_noChange() throws Exception {
        makeConfirmedCompleted();

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canceledPayload(orderId)))
                .andExpect(status().isOk());

        // 우리가 환불 요청한 적 없는 건은 보정 대상 아님 — 상태/재고 불변
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
    }
}
