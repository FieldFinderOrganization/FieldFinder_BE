package com.example.FieldFinder.service.impl;

package com.pitchbooking.application.service.impl;

import com.pitchbooking.application.dto.PaymentDTO;
import com.pitchbooking.application.entity.Payment;
import com.pitchbooking.application.mapper.PaymentMapper;
import com.pitchbooking.application.repository.PaymentRepository;
import com.pitchbooking.application.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;

    @Override
    public PaymentDTO processPayment(PaymentDTO paymentDTO) {
        Payment payment = PaymentMapper.INSTANCE.toEntity(paymentDTO);
        return PaymentMapper.INSTANCE.toDTO(paymentRepository.save(payment));
    }

    @Override
    public PaymentDTO getPaymentById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<PaymentDTO> getAllPayments() {
        return paymentRepository.findAll()
                .stream()
                .map(PaymentMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }
}
