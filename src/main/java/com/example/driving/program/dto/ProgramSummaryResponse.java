package com.example.driving.program.dto;

public record ProgramSummaryResponse(
        Long programIdx,
        String name,
        String vehicleName,
        String vehicleModel,
        Integer duration,
        Long amount
) {}
