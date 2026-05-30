package com.example.driving.program.domain;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.program.enums.ScheduleStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Schedule {

    @Id
    private Long scheduleIdx;
    private Long programIdx;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer capacity;
    private Integer remaining;
    private ScheduleStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isOpen() {
        return this.status == ScheduleStatus.OPEN;
    }

    public void decreaseRemaining() {
        if (this.remaining <= 0) {
            throw new BusinessException("잔여석이 없습니다.");
        }
        this.remaining -= 1;
        this.updatedAt = LocalDateTime.now();
    }

    public void increaseRemaining() {
        if (this.remaining >= this.capacity) {
            throw new BusinessException("잔여석이 정원을 초과할 수 없습니다.");
        }
        this.remaining += 1;
        this.updatedAt = LocalDateTime.now();
    }
}
