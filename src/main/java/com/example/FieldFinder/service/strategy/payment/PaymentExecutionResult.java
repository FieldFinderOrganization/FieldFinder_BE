package com.example.FieldFinder.service.strategy.payment;

public record PaymentExecutionResult(
        String checkoutUrl,
        String transactionId,
        String qrCode) {
}
