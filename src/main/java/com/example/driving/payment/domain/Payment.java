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
}
