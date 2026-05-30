package com.example.driving.program.repository;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.support.AbstractRepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Test
    @DisplayName("프로그램의 OPEN 일정만 시작 시간순으로 조회한다")
    void findByProgramIdxAndStatusOrderByStartAt() {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3")
                .createdAt(now).updatedAt(now)
                .build());
        Program program = programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        Long programIdx = program.getProgramIdx();
        scheduleRepository.save(buildSchedule(programIdx, LocalDateTime.of(2026, 6, 1, 14, 0), ScheduleStatus.OPEN));
        scheduleRepository.save(buildSchedule(programIdx, LocalDateTime.of(2026, 6, 1, 10, 0), ScheduleStatus.OPEN));
        scheduleRepository.save(buildSchedule(programIdx, LocalDateTime.of(2026, 6, 1, 12, 0), ScheduleStatus.CLOSED));

        List<Schedule> result =
                scheduleRepository.findByProgramIdxAndStatusOrderByStartAt(programIdx, ScheduleStatus.OPEN);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 0));
        assertThat(result.get(1).getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 14, 0));
    }

    private Schedule buildSchedule(Long programIdx, LocalDateTime startAt, ScheduleStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return Schedule.builder()
                .programIdx(programIdx)
                .startAt(startAt)
                .endAt(startAt.plusHours(1))
                .capacity(10).remaining(10)
                .status(status)
                .createdAt(now).updatedAt(now)
                .build();
    }
}
