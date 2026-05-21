package com.example.driving.program.dto;

import com.example.driving.program.domain.Schedule;
import com.example.driving.program.enums.ScheduleStatus;

import java.time.LocalDateTime;

public record ScheduleResponse(
        Long scheduleIdx,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer capacity,
        Integer remaining,
        ScheduleStatus status
) {
    public static ScheduleResponse from(Schedule schedule) {
        return new ScheduleResponse(
                schedule.getScheduleIdx(),
                schedule.getStartAt(),
                schedule.getEndAt(),
                schedule.getCapacity(),
                schedule.getRemaining(),
                schedule.getStatus()
        );
    }
}
