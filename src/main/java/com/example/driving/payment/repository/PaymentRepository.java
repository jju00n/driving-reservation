package com.example.driving.payment.repository;

import com.example.driving.payment.domain.Payment;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface PaymentRepository extends CrudRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);
}
