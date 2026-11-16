package com.example.driving.reservation.controller;

import com.example.driving.common.security.JwtProvider;
import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.domain.Payment;
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
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예약 상세 조회 통합 테스트 — 본인 조회(연관 조립) 200 / 결제 전 paymentStatus null / 없는 예약 404 / 남의 예약 403.
 * 컨테이너를 공유하므로 각 테스트는 setUp 에서 고유한 회원/스케줄/예약을 새로 만든다.
 */
@DisplayName("예약 상세 조회 통합 테스트")
class ReservationDetailControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 150_000L;
    private static final int CAPACITY = 5;
    private static final int REMAINING_AFTER_RESERVE = 4;

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
                .email("detail-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("user").phone("01000000000").role(Role.CUSTOMER)
                .createdAt(now).updatedAt(now).build());
        this.memberIdx = member.getMemberIdx();
        this.accessToken = jwtProvider.createAccessToken(memberIdx, Role.CUSTOMER.name());

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx()).name("M3 드라이빙 체험").duration(60).amount(AMOUNT)
                .status(ProgramStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        this.programIdx = program.getProgramIdx();
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(now.plusDays(2)).endAt(now.plusDays(2).plusHours(1))
                .capacity(CAPACITY).remaining(REMAINING_AFTER_RESERVE).status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now).build());
        this.scheduleIdx = schedule.getScheduleIdx();

        this.orderId = UUID.randomUUID().toString();
        Reservation reservation = reservationRepository.save(
                Reservation.create(memberIdx, scheduleIdx, programIdx, AMOUNT, orderId));
        this.reservationId = reservation.getReservationIdx();
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

    @Test
    @DisplayName("본인 예약 상세 조회 - 200 + 연관(프로그램/차량/스케줄/결제) 조립")
    void get_own_returnsDetail() throws Exception {
        makeConfirmed();

        mockMvc.perform(get("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationIdx").value(reservationId))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.amount").value(AMOUNT))
                .andExpect(jsonPath("$.data.program.name").value("M3 드라이빙 체험"))
                .andExpect(jsonPath("$.data.program.vehicle").value("BMW M3"))
                .andExpect(jsonPath("$.data.program.duration").value(60))
                .andExpect(jsonPath("$.data.schedule.startAt").exists())
                .andExpect(jsonPath("$.data.schedule.endAt").exists())
                .andExpect(jsonPath("$.data.paymentStatus").value("COMPLETED"));
    }

    @Test
    @DisplayName("결제 전(PAYMENT_PENDING) 예약 조회 - paymentStatus null")
    void get_beforePayment_paymentStatusNull() throws Exception {
        mockMvc.perform(get("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentStatus").doesNotExist());
    }

    @Test
    @DisplayName("없는 예약 조회 - 404")
    void get_notFound() throws Exception {
        mockMvc.perform(get("/reservations/{id}", 999_999_999L)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("다른 회원의 예약 조회 시도 - 403")
    void get_notOwner_forbidden() throws Exception {
        Member other = memberRepository.save(Member.builder()
                .email("other-" + UUID.randomUUID() + "@test.com").password("encoded")
                .name("other").phone("01011112222").role(Role.CUSTOMER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        String otherToken = jwtProvider.createAccessToken(other.getMemberIdx(), Role.CUSTOMER.name());

        mockMvc.perform(get("/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }
}
