package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.PaymentService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    public PaymentServiceImpl(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }
}
