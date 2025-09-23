package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PaymentService {
    PaymentResponseDTO createPaymentQRCode(PaymentRequestDTO requestDTO);
    List<PaymentResponseDTO> getPaymentsByUserId(UUID userId);

    List<PaymentResponseDTO> getAllPayments();
    void processWebhook(Map<String, Object> payload);
}
