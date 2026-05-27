package com.example.driving.program.repository;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.dto.ProgramSummaryResponse;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.support.AbstractRepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgramRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Test
    @DisplayName("ACTIVE 프로그램만 차량 정보와 함께 조회한다")
    void findActiveSummaries() {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3")
                .createdAt(now).updatedAt(now)
                .build());

        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("ACTIVE 프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now).updatedAt(now)
                .build());
        programRepository.save(Program.builder()
                .vehicleIdx(vehicle.getVehicleIdx())
                .name("INACTIVE 프로그램")
                .duration(60).amount(150000L)
                .status(ProgramStatus.INACTIVE)
                .createdAt(now).updatedAt(now)
                .build());

        List<ProgramSummaryResponse> result = programRepository.findActiveSummaries();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("ACTIVE 프로그램");
        assertThat(result.get(0).vehicleName()).isEqualTo("BMW");
        assertThat(result.get(0).vehicleModel()).isEqualTo("M3");
    }
}
