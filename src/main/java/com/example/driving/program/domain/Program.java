package com.example.driving.program.domain;

import com.example.driving.program.enums.ProgramStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("programs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Program {

    @Id
    private Long programIdx;
    private Long vehicleIdx;
    private String name;
    private Integer duration;
    private Long amount;
    private ProgramStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status == ProgramStatus.ACTIVE;
    }
}
