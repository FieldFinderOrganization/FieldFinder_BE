package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.WebhookRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody WebhookRequestDTO request) {
        String transactionId = request.getTransactionId();
        String status = request.getStatus();

        // 1. Tìm payment theo transactionId
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        // 2. Xử lý và kiểm tra trạng thái hợp lệ
        Booking.PaymentStatus newStatus;
        try {
            newStatus = Booking.PaymentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid payment status. Allowed values: PENDING, PAID, REFUNDED");
        }

        // 3. Cập nhật payment và booking
        payment.setPaymentStatus(newStatus);
        paymentRepository.save(payment);

        Booking booking = payment.getBooking();
        if (booking != null) {
            booking.setPaymentStatus(newStatus);
            bookingRepository.save(booking);
        }

        return ResponseEntity.ok("Payment and Booking payment status updated successfully");
    }

}
