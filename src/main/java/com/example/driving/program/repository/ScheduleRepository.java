package com.example.driving.program.repository;

import com.example.driving.program.domain.Schedule;
import com.example.driving.program.enums.ScheduleStatus;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScheduleRepository extends CrudRepository<Schedule, Long> {

    List<Schedule> findByProgramIdxAndStatusOrderByStartAt(Long programIdx, ScheduleStatus status);

    /**
     * 재고를 원자적으로 1 차감한다. {@code remaining > 0} 조건으로 음수 방지.
     * Redis 분산락(분산 제어) + DB 원자 UPDATE(최종 정합성)의 2중 방어.
     *
     * @return 영향받은 행 수. 0이면 잔여석 없음(차감 실패).
     */
    @Modifying
    @Query("UPDATE schedules SET remaining = remaining - 1, updated_at = NOW() " +
            "WHERE schedule_idx = :scheduleIdx AND remaining > 0")
    int decreaseRemainingIfAvailable(@Param("scheduleIdx") Long scheduleIdx);

    /**
     * 재고를 원자적으로 1 복구한다(결제 실패/취소/만료 등 예약 상태전이 시점).
     * {@code remaining < capacity} 조건으로 정원 초과 복구를 방지.
     *
     * @return 영향받은 행 수. 0이면 이미 정원만큼 차 있어 복구할 게 없음.
     */
    @Modifying
    @Query("UPDATE schedules SET remaining = remaining + 1, updated_at = NOW() " +
            "WHERE schedule_idx = :scheduleIdx AND remaining < capacity")
    int increaseRemaining(@Param("scheduleIdx") Long scheduleIdx);
}
