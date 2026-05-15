package com.example.FieldFinder.service.strategy.payment;

import com.example.FieldFinder.Enum.PaymentMethod;

public interface PaymentStrategy {
    PaymentMethod getMethod();

    PaymentExecutionResult execute(PaymentContext context);
}
