package com.example.FieldFinder.controller;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    @PostMapping("/create")
    public ResponseEntity<?> createPayment(@RequestBody PaymentRequestDTO requestDTO) {
        return ResponseEntity.ok(paymentService.createPaymentQRCode(requestDTO));
    }

}
