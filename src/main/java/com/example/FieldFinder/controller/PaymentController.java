package com.example.FieldFinder.controller;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.req.ShopPaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@CrossOrigin("*")
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
    // Get all payments
    @GetMapping
    public ResponseEntity<List<PaymentResponseDTO>> getAllPayments() {
        List<PaymentResponseDTO> payments = paymentService.getAllPayments();
        return ResponseEntity.ok(payments);
    }

    // Get payments by user ID
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<PaymentResponseDTO>> getPaymentsByUserId(@PathVariable UUID userId) {
        List<PaymentResponseDTO> payments = paymentService.getPaymentsByUserId(userId);
        return ResponseEntity.ok(payments);
    }

    @PostMapping("/create-shop-payment")
    public ResponseEntity<PaymentResponseDTO> createShopPayment(@RequestBody ShopPaymentRequestDTO request) {
        return ResponseEntity.ok(paymentService.createShopPayment(request));
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {
        paymentService.processWebhook(payload);
        return ResponseEntity.ok("Webhook received");
    }
}
