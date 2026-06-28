package com.example.driving.payment.domain;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.payment.enums.PaymentStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    private Long paymentIdx;
    private Long reservationIdx;
    private String paymentKey;
    private String orderId;
    private Long amount;
    private PaymentStatus status;
    private String failureCode;
    private String failureMessage;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 결제 승인(confirm) 요청 시점에 결제행을 REQUESTED 로 생성한다.
     * {@code uk_payments_order_id} 유니크 제약이 동시 중복 confirm 의 동시성 방어를 겸한다.
     */
    public static Payment request(Long reservationIdx, String orderId, Long amount, String paymentKey) {
        LocalDateTime now = LocalDateTime.now();
        return Payment.builder()
                .reservationIdx(reservationIdx)
                .orderId(orderId)
                .amount(amount)
                .paymentKey(paymentKey)
                .status(PaymentStatus.REQUESTED)
                .requestedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void complete(LocalDateTime approvedAt) {
        if (!this.status.isRequested()) {
            throw new BusinessException("결제 요청 상태에서만 완료 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.COMPLETED;
        this.approvedAt = approvedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String failureCode, String failureMessage) {
        if (!this.status.isRequested()) {
            throw new BusinessException("결제 요청 상태에서만 실패 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 환불 요청(외부 취소 호출 직전) — {@code COMPLETED → REFUND_REQUESTED}.
     * 토스 cancel API 호출 전에 미리 상태를 옮겨, 외부 호출이 미확정으로 끝나도 "환불 진행 중"임을 남긴다.
     */
    public void refundRequest() {
        if (!this.status.isCompleted()) {
            throw new BusinessException("결제 완료 상태에서만 환불을 요청할 수 있습니다.");
        }
        this.status = PaymentStatus.REFUND_REQUESTED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 환불 완료 — {@code REFUND_REQUESTED → REFUNDED}. 토스 취소 성공(또는 이미 취소됨) 확정 시점.
     */
    public void refund() {
        if (!this.status.isRefundRequested()) {
            throw new BusinessException("환불 요청 상태에서만 환불을 완료할 수 있습니다.");
        }
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 환불 요청 되돌림(보상) — {@code REFUND_REQUESTED → COMPLETED}.
     * 토스 cancel 이 "진짜 실패"(취소불가/기한초과/NOT_FOUND/키오류)로 거절됐을 때, 결제는 여전히
     * 완료 상태이므로 환불 요청을 원위치시킨다. 예약/재고는 건드리지 않는다(좌석·돈 모두 잃는 위험 방지).
     */
    public void cancelRefundRequest() {
        if (!this.status.isRefundRequested()) {
            throw new BusinessException("환불 요청 상태에서만 되돌릴 수 있습니다.");
        }
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }
}
