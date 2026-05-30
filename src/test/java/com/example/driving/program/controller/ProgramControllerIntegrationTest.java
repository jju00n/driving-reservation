package com.example.driving.program.controller;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨테이너를 공유하므로 테스트 간 DB 데이터가 누적된다.
 * 별도 cleanup 없이도 격리되도록, 전역 데이터(전체 ACTIVE 프로그램 목록 등)에 의존하지 않고
 * 각 테스트가 생성한 고유 데이터만 단언한다.
 */
class ProgramControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Test
    @DisplayName("프로그램 리스트 - ACTIVE 프로그램만 반환")
    void getPrograms_returnsOnlyActive() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        String activeName = "활성-" + UUID.randomUUID();
        String inactiveName = "비활성-" + UUID.randomUUID();

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());

        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name(activeName)
                .duration(60).amount(150000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name(inactiveName)
                .duration(60).amount(150000L)
                .status(ProgramStatus.INACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        mockMvc.perform(get("/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 내가 만든 ACTIVE 프로그램은 vehicleName 과 함께 포함된다
                .andExpect(jsonPath("$.data[?(@.name=='" + activeName + "')].vehicleName", hasItem("BMW")))
                // 내가 만든 INACTIVE 프로그램은 목록에 없어야 한다
                .andExpect(jsonPath("$.data[?(@.name=='" + inactiveName + "')].name").isEmpty());
    }

    @Test
    @DisplayName("프로그램 상세 - OPEN 일정과 함께 반환")
    void getProgram_returnsDetailWithOpenSchedules() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M5").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        scheduleRepository.save(Schedule.builder()
                .programIdx(program.getProgramIdx())
                .startAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 6, 1, 11, 0))
                .capacity(10).remaining(5)
                .status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now)
                .build());
        scheduleRepository.save(Schedule.builder()
                .programIdx(program.getProgramIdx())
                .startAt(LocalDateTime.of(2026, 6, 1, 13, 0))
                .endAt(LocalDateTime.of(2026, 6, 1, 14, 0))
                .capacity(10).remaining(0)
                .status(ScheduleStatus.CLOSED)
                .createdAt(now).updatedAt(now)
                .build());

        mockMvc.perform(get("/programs/" + program.getProgramIdx()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("프로그램"))
                .andExpect(jsonPath("$.data.vehicleName").value("BMW"))
                .andExpect(jsonPath("$.data.schedules.length()").value(1))
                .andExpect(jsonPath("$.data.schedules[0].remaining").value(5));
    }

    @Test
    @DisplayName("프로그램 상세 - 존재하지 않는 경우 404")
    void getProgram_notFound() throws Exception {
        mockMvc.perform(get("/programs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("프로그램 상세 - INACTIVE 프로그램은 404")
    void getProgram_inactive() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("비활성 프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.INACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        mockMvc.perform(get("/programs/" + program.getProgramIdx()))
                .andExpect(status().isNotFound());
    }
}
