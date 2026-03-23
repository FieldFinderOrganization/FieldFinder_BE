package com.example.FieldFinder.listener;

import com.example.FieldFinder.config.RabbitMQConfig;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EmailListener {

    private final BookingRepository bookingRepository;
    private final EmailService emailService;
    private final OrderRepository orderRepository;

    @RabbitListener(queues = RabbitMQConfig.BOOKING_EMAIL_QUEUE)
    public void handleBookingEmail(String bookingIdStr) {
        try {
            UUID bookingId = UUID.fromString(bookingIdStr);
            Booking booking = bookingRepository.findById(bookingId).orElse(null);

            if (booking != null) {
                emailService.sendBookingConfirmation(booking);
                System.out.println("✅ [RabbitMQ] Đã xử lý xong gửi email cho Booking ID: " + bookingId);
            } else {
                System.err.println("❌ [RabbitMQ] Không tìm thấy Booking ID: " + bookingId);
            }
        } catch (Exception e) {
            System.err.println("❌ [RabbitMQ] Lỗi khi xử lý message: " + e.getMessage());
        }
    }

    @RabbitListener(queues = RabbitMQConfig.ORDER_EMAIL_QUEUE)
    public void handleOrderEmail(String orderIdStr) {
        try {
            Long orderId = Long.parseLong(orderIdStr);
            Order order = orderRepository.findById(orderId).orElse(null);

            if (order != null) {
                emailService.sendOrderConfirmation(order);
                System.out.println("✅ [RabbitMQ] Đã xử lý xong gửi email cho Order ID: " + orderId);
            } else {
                System.err.println("❌ [RabbitMQ] Không tìm thấy Order ID: " + orderId);
            }
        } catch (Exception e) {
            System.err.println("❌ [RabbitMQ] Lỗi khi xử lý message order: " + e.getMessage());
        }
    }
}