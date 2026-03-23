package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Order;

public interface EmailService {
    void send(String to, String subject, String body);
    void sendOrderConfirmation(Order order);

    void sendBookingConfirmation(Booking booking);
}
