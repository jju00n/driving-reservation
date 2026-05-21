package com.example.driving.program.repository;

import com.example.driving.program.domain.Schedule;
import com.example.driving.program.enums.ScheduleStatus;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ScheduleRepository extends CrudRepository<Schedule, Long> {

    List<Schedule> findByProgramIdxAndStatusOrderByStartAt(Long programIdx, ScheduleStatus status);
}
