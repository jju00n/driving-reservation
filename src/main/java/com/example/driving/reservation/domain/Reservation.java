package com.example.driving.reservation.domain;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.reservation.enums.ReservationStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    private Long reservationIdx;
    private Long memberIdx;
    private Long scheduleIdx;
    private Long programIdx;
    private String orderId;
    private Long amount;
    private ReservationStatus status;
    private LocalDateTime reservedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Reservation create(Long memberIdx, Long scheduleIdx, Long programIdx, Long amount, String orderId) {
        LocalDateTime now = LocalDateTime.now();
        return Reservation.builder()
                .memberIdx(memberIdx)
                .scheduleIdx(scheduleIdx)
                .programIdx(programIdx)
                .orderId(orderId)
                .amount(amount)
                .status(ReservationStatus.PAYMENT_PENDING)
                .reservedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void confirm() {
        if (!this.status.isPaymentPending()) {
            throw new BusinessException("결제 대기 상태에서만 확정할 수 있습니다.");
        }
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail() {
        if (!this.status.isPaymentPending()) {
            throw new BusinessException("결제 대기 상태에서만 결제 실패 처리할 수 있습니다.");
        }
        this.status = ReservationStatus.PAYMENT_FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (!this.status.isPaymentPending() && !this.status.isConfirmed()) {
            throw new BusinessException("결제 대기 또는 예약 확정 상태에서만 취소할 수 있습니다.");
        }
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    public void expire() {
        if (!this.status.isPaymentPending()) {
            throw new BusinessException("결제 대기 상태에서만 만료 처리할 수 있습니다.");
        }
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }
}
