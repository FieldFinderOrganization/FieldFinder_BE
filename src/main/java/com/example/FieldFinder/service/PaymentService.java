package com.example.FieldFinder.service;

package com.pitchbooking.application.service;

import com.pitchbooking.application.dto.PaymentDTO;
import java.util.List;
import java.util.UUID;

public interface PaymentService {
    PaymentDTO processPayment(PaymentDTO paymentDTO);
    PaymentDTO getPaymentById(UUID paymentId);
    List<PaymentDTO> getAllPayments();
}
