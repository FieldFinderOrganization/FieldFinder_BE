package com.example.FieldFinder.service;

public interface AuthService {
    void sendLoginOtp(String email);
    boolean verifyOtp(String email, String code);
}

