package com.example.driving.program.dto;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;

import java.util.List;

public record ProgramDetailResponse(
        Long programIdx,
        String name,
        String vehicleName,
        String vehicleModel,
        Integer duration,
        Long amount,
        ProgramStatus status,
        List<ScheduleResponse> schedules
) {
    public static ProgramDetailResponse from(Program program, Vehicle vehicle, List<Schedule> schedules) {
        return new ProgramDetailResponse(
                program.getProgramIdx(),
                program.getName(),
                vehicle.getName(),
                vehicle.getModel(),
                program.getDuration(),
                program.getAmount(),
                program.getStatus(),
                schedules.stream().map(ScheduleResponse::from).toList()
        );
    }
}
