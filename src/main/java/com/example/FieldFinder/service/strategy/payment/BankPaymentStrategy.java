package com.example.FieldFinder.service.strategy.payment;

import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.service.impl.PayOSService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BankPaymentStrategy implements PaymentStrategy {

    private final PayOSService payOSService;

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.BANK;
    }

    @Override
    public PaymentExecutionResult execute(PaymentContext context) {
        int payOsOrderCode = generateOrderCode();
        PayOSService.PaymentResult result = payOSService.createPayment(
                context.amount(),
                payOsOrderCode,
                context.description(),
                context.returnUrl(),
                context.cancelUrl());
        return new PaymentExecutionResult(result.checkoutUrl(), result.paymentLinkId(), result.qrCode());
    }

    private int generateOrderCode() {
        int code = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        return code < 0 ? -code : code;
    }
}
