package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.Order;

public interface EmailService {
    void send(String to, String subject, String body);
    void sendOrderConfirmation(Order order);
}
