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
import org.springframework.http.HttpStatus;

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

    /**
     * 취소 가능 여부 검증(전이하지 않음) — 상태({@code PAYMENT_PENDING / CONFIRMED}) + 시점 가드.
     *
     * <p>시점 가드: 스케줄 시작({@code scheduleStartAt}) 전까지만 취소할 수 있다. 이미 시작한 프로그램은
     * 결제 여부와 무관하게 취소 의미가 없다. start_at 은 {@code Reservation} 이 직접 모르므로(schedule_idx 만 보유)
     * 서비스가 Schedule 을 조회해 값을 주입한다 — 시점 판단 <b>로직</b>은 도메인 안, 시점 <b>조회</b>는 서비스.
     *
     * <p>환불(외부 토스 호출)이 끼는 CONFIRMED 취소에서는 <b>토스 호출 전(TX1)에만</b> 이 검증을 수행한다.
     * 토스 환불이 성공한 뒤(TX2)에 시점 가드를 다시 걸면, 환불은 됐는데 예약 전이는 실패하는 정합성 붕괴가
     * 생기므로, 전이({@link #cancel})는 시점을 재검증하지 않는다.
     */
    public void ensureCancelable(LocalDateTime scheduleStartAt, LocalDateTime now) {
        if (!this.status.isPaymentPending() && !this.status.isConfirmed()) {
            throw new BusinessException("결제 대기 또는 예약 확정 상태에서만 취소할 수 있습니다.");
        }
        if (!now.isBefore(scheduleStartAt)) {
            throw new BusinessException("이미 시작된 프로그램은 취소할 수 없습니다.", HttpStatus.CONFLICT);
        }
    }

    /**
     * 예약 취소 전이 — {@code PAYMENT_PENDING / CONFIRMED → CANCELLED}.
     * 시점 가드는 {@link #ensureCancelable} 가 담당하며, 여기서는 상태 가드만 둔다(전이는 시점 무관).
     */
    public void cancel(LocalDateTime now) {
        if (!this.status.isPaymentPending() && !this.status.isConfirmed()) {
            throw new BusinessException("결제 대기 또는 예약 확정 상태에서만 취소할 수 있습니다.");
        }
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void expire() {
        if (!this.status.isPaymentPending()) {
            throw new BusinessException("결제 대기 상태에서만 만료 처리할 수 있습니다.");
        }
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }
}
