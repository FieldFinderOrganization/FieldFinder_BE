package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.PaymentDto;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.mapper.PaymentMapper;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.PaymentService;
import lombok.RequiredArgsConstructor;
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
    @Override
    public PaymentDto processPayment(PaymentDto paymentDTO) {
        Payment payment = PaymentMapper.INSTANCE.toEntity(paymentDTO);
        return PaymentMapper.INSTANCE.toDTO(paymentRepository.save(payment));
    }

    @Override
    public PaymentDto getPaymentById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<PaymentDto> getAllPayments() {
        return paymentRepository.findAll()
                .stream()
                .map(PaymentMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PaymentDto createPayment(PaymentDto payment) {
        return null;
    }

    @Override
    public PaymentDto updatePayment(UUID id, PaymentDto payment) {
        return null;
    }

    @Override
    public void deletePayment(UUID id) {

    }
}
