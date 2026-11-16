package com.example.driving.reservation.controller;

import com.example.driving.common.security.JwtProvider;
import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossCancelResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.payment.exception.PaymentGatewayUnavailableException;
import com.example.driving.payment.exception.TossPaymentException;
import com.example.driving.payment.repository.PaymentRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예약 취소/환불 컨트롤러 통합 테스트 — 실제 DB(Testcontainers) + 토스는 {@link TossPaymentClient} Mock 으로 차단.
 * confirm 의 거울상으로, 취소 전 분기를 DB 상태(예약/결제/재고)까지 검증한다:
 * 결제전 취소 / 결제후 환불 / 멱등(이미취소) / 진짜실패 원복 / 미확정(503) / 시점초과 / 소유권 / 중복취소.
 *
 * 컨테이너를 공유하므로 각 테스트는 setUp 에서 고유한 회원/스케줄/예약을 새로 만든다.
 */
@DisplayName("예약 취소 컨트롤러 통합 테스트")
class ReservationCancelControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4; // 예약 신청으로 1 차감된 상태

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
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

    private String accessToken;
    private Long memberIdx;
    private Long programIdx;
    private Long scheduleIdx;
    private Long reservationId;
    private String orderId;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        Member member = memberRepository.save(Member.builder()
                .email("cancel-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000")
                .role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now)
                .build());
        this.memberIdx = member.getMemberIdx();
        this.accessToken = jwtProvider.createAccessToken(memberIdx, Role.CUSTOMER.name());

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("M3 드라이빙 체험").duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        this.programIdx = program.getProgramIdx();
        this.scheduleIdx = createSchedule(now.plusDays(1)); // 시작 전(취소 가능)

        this.orderId = UUID.randomUUID().toString();
        Reservation reservation = reservationRepository.save(
                Reservation.create(memberIdx, scheduleIdx, programIdx, AMOUNT, orderId));
        this.reservationId = reservation.getReservationIdx();
    }

    private Long createSchedule(LocalDateTime startAt) {
        LocalDateTime now = LocalDateTime.now();
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(startAt).endAt(startAt.plusHours(1))
                .capacity(CAPACITY).remaining(REMAINING_AFTER_RESERVE)
                .status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now)
                .build());
        return schedule.getScheduleIdx();
    }

    /** setUp 의 PENDING 예약을 결제 완료(CONFIRMED + 결제 COMPLETED)로 만든다. */
    private void makeConfirmed() {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        reservation.confirm();
        reservationRepository.save(reservation);

        Payment payment = Payment.request(reservationId, orderId, AMOUNT, "pk_" + orderId);
        payment.complete(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    private TossCancelResponse tossCanceled() {
        return new TossCancelResponse("pk_" + orderId, orderId, "CANCELED");
    }

    // ---------- [A] 결제 전 취소 ----------

    @Test
    @DisplayName("결제 전(PAYMENT_PENDING) 취소 - 200 + 예약 CANCELLED + 재고 복구, 토스 미호출")
    void cancel_pending() throws Exception {
        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationStatus").value("CANCELLED"));

        verify(tossPaymentClient, never()).cancel(any(), any(), any());
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(CAPACITY);
    }

    // ---------- [B] 결제 후 환불 ----------

    @Test
    @DisplayName("결제 후(CONFIRMED) 취소 - 토스 환불 성공 → 200 + 예약 CANCELLED + 결제 REFUNDED + 재고 복구")
    void cancel_confirmed_refundSuccess() throws Exception {
        makeConfirmed();
        given(tossPaymentClient.cancel(any(), any(), any())).willReturn(tossCanceled());

        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"));

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("이미 취소된 결제(ALREADY_CANCELED_PAYMENT) - 멱등 성공 → 200 + CANCELLED + REFUNDED + 재고 복구")
    void cancel_alreadyCanceledOnToss_idempotent() throws Exception {
        makeConfirmed();
        given(tossPaymentClient.cancel(any(), any(), any()))
                .willThrow(new TossPaymentException("ALREADY_CANCELED_PAYMENT", "이미 취소된 결제입니다."));

        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"));

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("환불 진짜 실패(NOT_CANCELABLE) - 409 + 예약 CONFIRMED 유지 + 결제 COMPLETED 원복 + 재고 불변")
    void cancel_refundReject_keepsSeat() throws Exception {
        makeConfirmed();
        given(tossPaymentClient.cancel(any(), any(), any()))
                .willThrow(new TossPaymentException("NOT_CANCELABLE_PAYMENT", "취소할 수 없는 결제입니다."));

        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 환불 실패 → 좌석/돈 모두 잃는 위험 방지: 예약 CONFIRMED 유지, 결제 COMPLETED 원복, 재고 불변
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
    }

    @Test
    @DisplayName("환불 미확정(게이트웨이 장애) - 503 + 예약 CONFIRMED + 결제 REFUND_REQUESTED 잔류 + 재고 불변")
    void cancel_gatewayUnavailable_pendingRefund() throws Exception {
        makeConfirmed();
        given(tossPaymentClient.cancel(any(), any(), any()))
                .willThrow(new PaymentGatewayUnavailableException("결제 취소 서비스를 일시적으로 사용할 수 없습니다.", new RuntimeException()));

        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));

        // 미확정: 상태 그대로(REFUND_REQUESTED 잔류 = 재시도 대상), 재고 복구 안 함
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUND_REQUESTED);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
    }

    // ---------- 가드 ----------

    @Test
    @DisplayName("시작 지난 스케줄 취소 - 409 + 토스 미호출 + 상태 불변")
    void cancel_afterStart_conflict() throws Exception {
        // 과거 시작 스케줄로 CONFIRMED 예약 구성
        Long pastSchedule = createSchedule(LocalDateTime.now().minusMinutes(1));
        String pastOrderId = UUID.randomUUID().toString();
        Reservation reservation = reservationRepository.save(
                Reservation.create(memberIdx, pastSchedule, programIdx, AMOUNT, pastOrderId));
        reservation.confirm();
        reservationRepository.save(reservation);
        Payment payment = Payment.request(reservation.getReservationIdx(), pastOrderId, AMOUNT, "pk_" + pastOrderId);
        payment.complete(LocalDateTime.now());
        paymentRepository.save(payment);

        mockMvc.perform(delete("/reservations/{id}", reservation.getReservationIdx())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 시작된 프로그램은 취소할 수 없습니다."));

        verify(tossPaymentClient, never()).cancel(any(), any(), any());
        assertThat(reservationRepository.findByOrderId(pastOrderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("다른 회원의 예약 취소 시도 - 403 + 토스 미호출 + 상태 불변")
    void cancel_notOwner_forbidden() throws Exception {
        Member other = memberRepository.save(Member.builder()
                .email("other-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("other").phone("01011112222")
                .role(Role.CUSTOMER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        String otherToken = jwtProvider.createAccessToken(other.getMemberIdx(), Role.CUSTOMER.name());

        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verify(tossPaymentClient, never()).cancel(any(), any(), any());
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("이미 취소된 예약 재취소 - 멱등 200(중복 재고 복구 없음)")
    void cancel_alreadyCanceled_idempotent() throws Exception {
        // 1차 취소(결제 전)
        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(CAPACITY);

        // 2차 취소 — 이미 CANCELLED 라 멱등 반환, 재고 중복 복구 없음
        mockMvc.perform(delete("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationStatus").value("CANCELLED"));

        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining()).isEqualTo(CAPACITY);
    }
}
