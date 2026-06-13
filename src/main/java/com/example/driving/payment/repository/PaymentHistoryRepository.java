package com.example.driving.payment.repository;

import com.example.driving.payment.domain.PaymentHistory;
import org.springframework.data.repository.CrudRepository;

public interface PaymentHistoryRepository extends CrudRepository<PaymentHistory, Long> {
}
