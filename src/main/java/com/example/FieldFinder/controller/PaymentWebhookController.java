package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.WebhookRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository; // ✅ thêm dòng này

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody WebhookRequestDTO request) {
        String transactionId = request.getData().getTransactionId();

        if ("124c33293c43417ab7879e14c8d9eb18".equals(transactionId)) {
            log.info("✅ Received test webhook. Ignoring.");
            return ResponseEntity.ok("Test webhook received");
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        // Vì webhook gửi về là đã thanh toán thành công
        Booking.PaymentStatus newStatus = Booking.PaymentStatus.PAID;

        // Cập nhật payment
        payment.setPaymentStatus(newStatus);
        paymentRepository.save(payment);

        // Cập nhật booking nếu có
        Booking booking = payment.getBooking();
        if (booking != null) {
            booking.setPaymentStatus(newStatus);
            if (newStatus == Booking.PaymentStatus.PAID) {
                booking.setStatus(Booking.BookingStatus.CONFIRMED);
            }
            bookingRepository.save(booking);
        }

        return ResponseEntity.ok("✅ Payment and Booking payment status updated successfully");
    }


    @GetMapping("/thanks")
    public String thankYouPage() {
        return "Thank you for your payment!";
    }

}
