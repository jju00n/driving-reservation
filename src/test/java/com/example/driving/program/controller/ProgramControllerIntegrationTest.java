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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProgramControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @BeforeEach
    void cleanup() {
        scheduleRepository.deleteAll();
        programRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    @Test
    @DisplayName("프로그램 리스트 - ACTIVE 프로그램만 반환")
    void getPrograms_returnsOnlyActive() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build());

        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("활성 프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("비활성 프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.INACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        mockMvc.perform(get("/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("활성 프로그램"))
                .andExpect(jsonPath("$.data[0].vehicleName").value("BMW"));
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
