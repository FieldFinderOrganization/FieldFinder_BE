package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.PaymentDto;

import java.util.List;
import java.util.UUID;

public interface PaymentService {
    PaymentDto processPayment(PaymentDto paymentDTO);
    PaymentDto getPaymentById(UUID paymentId);
    List<PaymentDto> getAllPayments();

    PaymentDto createPayment(PaymentDto payment);

    PaymentDto updatePayment(UUID id, PaymentDto payment);

    void deletePayment(UUID id);
}
