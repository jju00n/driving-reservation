package com.example.driving.program.repository;

import com.example.driving.program.domain.Program;
import com.example.driving.program.dto.ProgramSummaryResponse;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ProgramRepository extends CrudRepository<Program, Long> {

    @Query("""
            SELECT p.program_idx, p.name, p.duration, p.amount,
                   v.name AS vehicle_name, v.model AS vehicle_model
            FROM programs p
            JOIN vehicles v ON p.vehicle_idx = v.vehicle_idx
            WHERE p.status = 'ACTIVE'
            ORDER BY p.program_idx
            """)
    List<ProgramSummaryResponse> findActiveSummaries();
}
