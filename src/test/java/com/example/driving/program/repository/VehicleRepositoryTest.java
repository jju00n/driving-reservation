package com.example.driving.program.repository;

import com.example.driving.program.domain.Vehicle;
import com.example.driving.support.AbstractRepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private VehicleRepository vehicleRepository;

    @Test
    @DisplayName("차량을 저장하고 ID로 조회한다")
    void saveAndFindById() {
        LocalDateTime now = LocalDateTime.now();
        Vehicle saved = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M5")
                .createdAt(now).updatedAt(now)
                .build());

        Optional<Vehicle> found = vehicleRepository.findById(saved.getVehicleIdx());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("BMW");
        assertThat(found.get().getModel()).isEqualTo("M5");
    }
}
