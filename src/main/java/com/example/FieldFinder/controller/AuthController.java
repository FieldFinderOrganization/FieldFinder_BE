package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.VerifyOtpRequestDTO;
import com.example.FieldFinder.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.FieldFinder.dto.req.VerifyOtpRequestDTO;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/send-otp")
    public ResponseEntity<String> sendOtp(@RequestParam String email) {
        authService.sendLoginOtp(email);
        return ResponseEntity.ok("OTP sent successfully");
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<String> verifyOtp(@RequestBody VerifyOtpRequestDTO req) {
        authService.verifyOtp(req.getEmail(), req.getCode());
        return ResponseEntity.ok("Login success!");
    }
}
