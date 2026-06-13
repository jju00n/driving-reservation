package com.example.driving.payment.controller;

import com.example.driving.common.security.JwtProvider;
import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.client.TossPaymentClient;
import com.example.driving.payment.client.dto.TossConfirmResponse;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.dto.PaymentConfirmRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 승인 컨트롤러 통합 테스트 — 실제 DB(Testcontainers) + 토스는 {@link TossPaymentClient} Mock 으로 차단.
 * confirm 의 전 분기(성공/금액불일치/거절+재고복구/멱등/게이트웨이장애/인증/미존재)를 DB 상태까지 검증한다.
 *
 * 컨테이너를 공유하므로 각 테스트는 setUp 에서 고유한 회원/스케줄/예약(UUID orderId)을 새로 만든다.
 */
@DisplayName("결제 승인 컨트롤러 통합 테스트")
class PaymentControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4; // 예약 신청으로 이미 1 차감된 상태

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    private String orderId;
    private Long scheduleIdx;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        Member member = memberRepository.save(Member.builder()
                .email("payment-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000")
                .role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now)
                .build());
        this.accessToken = jwtProvider.createAccessToken(member.getMemberIdx(), Role.CUSTOMER.name());

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("M3 드라이빙 체험")
                .duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(program.getProgramIdx())
                .startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(CAPACITY).remaining(REMAINING_AFTER_RESERVE)
                .status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now)
                .build());
        this.scheduleIdx = schedule.getScheduleIdx();

        this.orderId = UUID.randomUUID().toString();
        reservationRepository.save(Reservation.create(
                member.getMemberIdx(), scheduleIdx, program.getProgramIdx(), AMOUNT, orderId));
    }

    private String confirmBody(String paymentKey, String orderId, long amount) {
        return objectMapper.writeValueAsString(new PaymentConfirmRequest(paymentKey, orderId, amount));
    }

    private TossConfirmResponse tossDone(String paymentKey) {
        return new TossConfirmResponse(paymentKey, orderId, "DONE", OffsetDateTime.now());
    }

    @Test
    @DisplayName("결제 승인 성공 - 200 + 예약 CONFIRMED / 결제 COMPLETED")
    void confirm_success() throws Exception {
        given(tossPaymentClient.confirm(any())).willReturn(tossDone("pk_success"));

        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_success", orderId, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getPaymentKey()).isEqualTo("pk_success");
        assertThat(payment.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("금액 위변조 - 400 + 토스 미호출 + 상태 불변")
    void confirm_fail_amountMismatch() throws Exception {
        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_x", orderId, 999_999L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("결제 금액이 일치하지 않습니다."));

        verify(tossPaymentClient, never()).confirm(any());
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("토스 거절(4xx) - 400 + 예약 PAYMENT_FAILED + 재고 복구")
    void confirm_fail_tossRejected() throws Exception {
        given(tossPaymentClient.confirm(any()))
                .willThrow(new TossPaymentException("REJECT_CARD_COMPANY", "한도 초과로 거절되었습니다."));

        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_reject", orderId, AMOUNT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("한도 초과로 거절되었습니다."));

        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_FAILED);
        // 재고 복구: 4 → 5
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(CAPACITY);
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("중복 confirm - 두 번째는 토스 재호출 없이 멱등 200")
    void confirm_idempotent() throws Exception {
        given(tossPaymentClient.confirm(any())).willReturn(tossDone("pk_idem"));

        // 1차 승인
        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_idem", orderId, AMOUNT)))
                .andExpect(status().isOk());

        // 2차 confirm — 이미 CONFIRMED 라 멱등 반환
        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_idem", orderId, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(tossPaymentClient, times(1)).confirm(any()); // 토스는 1번만
        assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
    }

    @Test
    @DisplayName("게이트웨이 장애 - 503 + 상태/재고 불변(미확정)")
    void confirm_fail_gatewayUnavailable() throws Exception {
        given(tossPaymentClient.confirm(any()))
                .willThrow(new PaymentGatewayUnavailableException("결제 서비스를 일시적으로 사용할 수 없습니다.", new RuntimeException()));

        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_gw", orderId, AMOUNT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));

        // 결과 미확정: 예약 PENDING 유지, 재고 복구 안 함
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
        assertThat(scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining())
                .isEqualTo(REMAINING_AFTER_RESERVE);
        // 결제행은 REQUESTED 로 남아 2차 보정 대상
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    @DisplayName("다른 회원의 예약 결제 시도 - 403 Forbidden + 토스 미호출")
    void confirm_fail_notOwner() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Member other = memberRepository.save(Member.builder()
                .email("other-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("other").phone("01011112222").role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now).build());
        String otherToken = jwtProvider.createAccessToken(other.getMemberIdx(), Role.CUSTOMER.name());

        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_x", orderId, AMOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verify(tossPaymentClient, never()).confirm(any());
        // 피해자 예약은 그대로 PAYMENT_PENDING (강제 실패 안 됨)
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("인증 없음 - 403 Forbidden")
    void confirm_unauthorized() throws Exception {
        mockMvc.perform(post("/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_x", orderId, AMOUNT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 orderId - 404 Not Found")
    void confirm_fail_reservationNotFound() throws Exception {
        mockMvc.perform(post("/payments/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("pk_x", "no-such-order-" + UUID.randomUUID(), AMOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
