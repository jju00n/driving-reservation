package com.example.driving.payment.controller;

import com.example.driving.payment.dto.PaymentConfirmRequest;
import com.example.driving.payment.dto.PaymentConfirmResponse;
import com.example.driving.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/confirm")
    public PaymentConfirmResponse confirm(
            @AuthenticationPrincipal Long memberIdx,
            @Valid @RequestBody PaymentConfirmRequest request
    ) {
        return paymentService.confirm(memberIdx, request.paymentKey(), request.orderId(), request.amount());
    }
}
