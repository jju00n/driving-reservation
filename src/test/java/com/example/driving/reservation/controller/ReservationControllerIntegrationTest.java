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
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨테이너를 공유하므로 테스트 간 DB 데이터가 누적된다.
 * 별도 cleanup 없이도 격리되도록, 각 테스트는 setUp 에서 고유한 회원/프로그램/스케줄을 새로 만들고
 * 전역 카운트가 아니라 자신이 만든 scheduleIdx/orderId 단위로만 단언한다.
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
    @Autowired
    private ReservationRepository reservationRepository;

    private Long memberIdx;
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
        this.memberIdx = member.getMemberIdx();
        this.accessToken = jwtProvider.createAccessToken(memberIdx, Role.CUSTOMER.name());

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

    private int remaining() {
        return scheduleRepository.findById(scheduleIdx).orElseThrow().getRemaining();
    }

    @Test
    @DisplayName("인증 없이 예약 시도 - 403 Forbidden (Spring Security 기본 동작)")
    void create_unauthorized() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, scheduleIdx);

        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(remaining()).isEqualTo(5); // 차감되지 않음
    }

    @Test
    @DisplayName("정상 예약 - 201 Created + orderId/amount/reservationIdx 반환")
    void create_success() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(programIdx, scheduleIdx);

        MvcResult result = mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationIdx").isNumber())
                .andExpect(jsonPath("$.data.orderId").isNotEmpty())
                .andExpect(jsonPath("$.data.amount").value(150_000))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String orderId = body.get("data").get("orderId").asText();
        assertThat(reservationRepository.findByOrderId(orderId)).isPresent();
        assertThat(remaining()).isEqualTo(4);
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

        assertThat(remaining()).isEqualTo(5);
    }

    @Test
    @DisplayName("programIdx 불일치 - 400 Bad Request")
    void create_fail_programMismatch() throws Exception {
        Long wrongProgramIdx = programIdx + 999L;
        CreateReservationRequest request = new CreateReservationRequest(wrongProgramIdx, scheduleIdx);

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("스케줄과 프로그램이 일치하지 않습니다."));

        assertThat(remaining()).isEqualTo(5);
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

        assertThat(remaining()).isEqualTo(5); // 내 스케줄은 그대로
    }
}
