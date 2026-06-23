package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.UserTierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final UserTierService userTierService;
    private final NotificationService notificationService;
    private final com.example.FieldFinder.service.WalletTopupService walletTopupService;
    private final com.example.FieldFinder.service.impl.PayOsWebhookVerifier payOsWebhookVerifier;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody java.util.Map<String, Object> payload) {
        // Chống webhook giả: xác minh chữ ký PayOS (mode log/enforce).
        if (!payOsWebhookVerifier.allow(payload)) {
            return ResponseEntity.status(400).body("Invalid signature");
        }

        Object dataObj = payload.get("data");
        String transactionId = (dataObj instanceof java.util.Map<?, ?> data)
                ? (data.get("paymentLinkId") == null ? null : String.valueOf(data.get("paymentLinkId")))
                : null;

        if ("124c33293c43417ab7879e14c8d9eb18".equals(transactionId)) {
            log.info("✅ Received test webhook. Ignoring.");
            return ResponseEntity.ok("Test webhook received");
        }
        if (transactionId == null) {
            return ResponseEntity.badRequest().body("Missing paymentLinkId");
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId).orElse(null);
        if (payment == null) {
            // Có thể là lệnh NẠP VÍ chủ sân (xác nhận server-side + cộng ví, idempotent).
            if (walletTopupService.handlePaidWebhook(transactionId)) {
                return ResponseEntity.ok("Wallet topup processed");
            }
            throw new RuntimeException("Payment not found");
        }

        PaymentStatus newStatus = PaymentStatus.PAID;

        payment.setPaymentStatus(newStatus);
        paymentRepository.save(payment);

        Booking booking = payment.getBooking();
        if (booking != null) {
            booking.setPaymentStatus(newStatus);
            if (newStatus == PaymentStatus.PAID) {
                boolean wasConfirmed = booking.getStatus() == BookingStatus.CONFIRMED;
                booking.setStatus(BookingStatus.CONFIRMED);
                if (!wasConfirmed) {
                    notificationService.notifyBookingConfirmed(booking);
                }
            }
            bookingRepository.save(booking);
        }

        Order order = payment.getOrder();
        if (order != null && newStatus == PaymentStatus.PAID) {
            boolean wasConfirmed = order.getStatus() == OrderStatus.CONFIRMED;
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);

            if (order.getUser() != null) {
                userTierService.recalcTier(order.getUser().getUserId());
                if (!wasConfirmed) {
                    notificationService.notify(order.getUser().getUserId(),
                            "ORDER_CONFIRMED",
                            "Đơn hàng #" + order.getOrderId() + " đã xác nhận",
                            "Thanh toán thành công, đơn hàng đang được chuẩn bị.",
                            "ORDER", String.valueOf(order.getOrderId()));
                }
            }
        }

        return ResponseEntity.ok("✅ Payment and Booking payment status updated successfully");
    }

    @GetMapping("/thanks")
    public String thankYouPage() {
        return "Thank you for your payment!";
    }
}