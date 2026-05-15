package com.example.FieldFinder.service.strategy.payment;

import java.math.BigDecimal;

public record PaymentContext(
        BigDecimal amount,
        Long orderId,
        String description,
        String returnUrl,
        String cancelUrl) {
}
