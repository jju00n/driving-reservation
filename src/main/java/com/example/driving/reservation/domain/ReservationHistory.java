package com.example.driving.reservation.domain;

import com.example.driving.reservation.enums.ReservationStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("reservation_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReservationHistory {

    @Id
    private Long historyIdx;
    private Long reservationIdx;
    private ReservationStatus status;
    private LocalDateTime createdAt;

    public static ReservationHistory of(Long reservationIdx, ReservationStatus status) {
        return ReservationHistory.builder()
                .reservationIdx(reservationIdx)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
