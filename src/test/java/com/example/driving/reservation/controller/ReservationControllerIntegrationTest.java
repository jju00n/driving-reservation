package com.example.driving.reservation.controller;

import com.example.driving.common.security.JwtProvider;
import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.dto.CreateReservationRequest;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 통합 테스트 — endpoint 자체의 책임만 검증한다.
 * (요청 바인딩 / Validation / 인증 / 예외→상태코드 변환(Advice) / 응답 봉투 포맷)
 *
 * 재고 차감·중복예약 멱등성 등 비즈니스 로직/DB 부작용은 서비스 계층 테스트의 책임이다
 * (ReservationServiceTest, ReservationConcurrencyIntegrationTest). 여기서 중복 검증하지 않는다.
 *
 * 컨테이너를 공유하므로 각 테스트는 setUp 에서 고유한 회원/프로그램/스케줄을 새로 만든다(UUID 이메일).
 */
@DisplayName("예약 신청 컨트롤러 통합 테스트")
class ReservationControllerIntegrationTest extends AbstractIntegrationTest {

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

    private String accessToken;
    private Long programIdx;
    private Long scheduleIdx;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        Member member = memberRepository.save(Member.builder()
                .email("reservation-" + UUID.randomUUID() + "@test.com").password("encoded")
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
                .duration(60).amount(150_000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        this.programIdx = program.getProgramIdx();

        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx)
                .startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(5).remaining(5)
                .status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now)
                .build());
        this.scheduleIdx = schedule.getScheduleIdx();
    }

    @Test
    @DisplayName("인증 없이 예약 시도 - 403 Forbidden (Spring Security 기본 동작)")
    void create_unauthorized() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, scheduleIdx);

        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("정상 예약 - 201 Created + 응답 봉투(success/data) 포맷")
    void create_success() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, scheduleIdx);

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationIdx").isNumber())
                .andExpect(jsonPath("$.data.orderId").isNotEmpty())
                .andExpect(jsonPath("$.data.amount").value(150_000));
    }

    @Test
    @DisplayName("programIdx 누락 - 400 Bad Request (Validation)")
    void create_fail_missingProgramIdx() throws Exception {
        String body = "{\"scheduleIdx\":" + scheduleIdx + "}";

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("programIdx 불일치 - 400 Bad Request + 메시지")
    void create_fail_programMismatch() throws Exception {
        Long wrongProgramIdx = programIdx + 999L;
        CreateReservationRequest request = new CreateReservationRequest(wrongProgramIdx, scheduleIdx);

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("스케줄과 프로그램이 일치하지 않습니다."));
    }

    @Test
    @DisplayName("같은 회원·같은 스케줄 중복 예약 - 409 Conflict + 메시지")
    void create_fail_duplicateReservation() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, scheduleIdx);

        // 1차 예약 성공
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // 2차 예약 - 같은 회원·같은 스케줄 → 409 Conflict 로 변환되는지(Advice)
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이미 해당 스케줄에 예약이 있습니다."));
    }

    @Test
    @DisplayName("존재하지 않는 scheduleIdx - 404 Not Found")
    void create_fail_scheduleNotFound() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, 999_999L);

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
