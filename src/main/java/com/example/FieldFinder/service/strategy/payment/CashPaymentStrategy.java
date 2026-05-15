package com.example.FieldFinder.service.strategy.payment;

import com.example.FieldFinder.Enum.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class CashPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.CASH;
    }

    @Override
    public PaymentExecutionResult execute(PaymentContext context) {
        String transactionId = "COD-" + System.currentTimeMillis();
        return new PaymentExecutionResult(context.returnUrl(), transactionId, null);
    }
}
