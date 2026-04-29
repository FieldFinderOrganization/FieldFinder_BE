package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.RefundRequest;

public interface EmailService {
    void send(String to, String subject, String body);
    void sendOrderConfirmation(Order order);
    void sendOrderCancellation(Order order);
    void sendOrderPaymentReminder(Order order);

    void sendBookingConfirmation(Booking booking);
    void sendBookingCancellation(Booking booking);
    void sendBookingPaymentReminder(Booking booking);

    void sendRefundCodeIssued(RefundRequest refundRequest);
}