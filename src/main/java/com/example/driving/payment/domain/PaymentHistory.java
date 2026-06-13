package com.example.driving.payment.domain;

import com.example.driving.payment.enums.PaymentStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("payment_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentHistory {

    @Id
    private Long historyIdx;
    private Long paymentIdx;
    private PaymentStatus status;
    private LocalDateTime createdAt;

    public static PaymentHistory of(Long paymentIdx, PaymentStatus status) {
        return PaymentHistory.builder()
                .paymentIdx(paymentIdx)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
