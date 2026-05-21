package com.example.driving.program.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.dto.ProgramDetailResponse;
import com.example.driving.program.dto.ProgramSummaryResponse;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProgramService {

    private final ProgramRepository programRepository;
    private final VehicleRepository vehicleRepository;
    private final ScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public List<ProgramSummaryResponse> getPrograms() {
        return programRepository.findActiveSummaries();
    }

    @Transactional(readOnly = true)
    public ProgramDetailResponse getProgram(Long programIdx) {
        Program program = programRepository.findById(programIdx)
                .orElseThrow(() -> new BusinessException("프로그램을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!program.isActive()) {
            throw new BusinessException("종료된 프로그램입니다.", HttpStatus.NOT_FOUND);
        }

        Vehicle vehicle = vehicleRepository.findById(program.getVehicleIdx())
                .orElseThrow(() -> new BusinessException("차량 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        List<Schedule> schedules =
                scheduleRepository.findByProgramIdxAndStatusOrderByStartAt(programIdx, ScheduleStatus.OPEN);

        return ProgramDetailResponse.from(program, vehicle, schedules);
    }
}
