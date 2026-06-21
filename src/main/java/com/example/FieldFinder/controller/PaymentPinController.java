package com.example.FieldFinder.controller;

import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.PaymentPinService;
import com.example.FieldFinder.service.PaymentPinService.PinStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Mã PIN thanh toán: đặt/đổi/xác thực/reset. */
@RestController
@RequestMapping("/api/wallet/pin")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class PaymentPinController {

    private final PaymentPinService pinService;
    private final UserRepository userRepository;

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        PinStatus s = pinService.status(userId);
        return ResponseEntity.ok(Map.of(
                "hasPin", s.hasPin(),
                "locked", s.locked(),
                "lockedUntil", s.lockedUntil() != null ? s.lockedUntil().toString() : ""));
    }

    @PostMapping("/set")
    public ResponseEntity<?> set(@RequestBody Map<String, String> body, Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        pinService.setPin(userId, body.get("pin"));
        return ResponseEntity.ok(Map.of("message", "Đã đặt PIN."));
    }

    @PostMapping("/change")
    public ResponseEntity<?> change(@RequestBody Map<String, String> body, Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        pinService.changePin(userId, body.get("currentPin"), body.get("newPin"));
        return ResponseEntity.ok(Map.of("message", "Đã đổi PIN."));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body, Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        pinService.verifyPin(userId, body.get("pin"));
        return ResponseEntity.ok(Map.of("verified", true));
    }

    @PostMapping("/forgot")
    public ResponseEntity<?> forgot(Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        pinService.requestReset(userId);
        return ResponseEntity.ok(Map.of("message", "Đã gửi mã OTP về email."));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(@RequestBody Map<String, String> body, Authentication auth) {
        UUID userId = uid(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        pinService.resetPin(userId, body.get("otp"), body.get("newPin"));
        return ResponseEntity.ok(Map.of("message", "Đã đặt lại PIN."));
    }

    private UUID uid(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            Object p = authentication.getPrincipal();
            String email = (p instanceof UserDetails ud) ? ud.getUsername() : (p instanceof String s ? s : null);
            if (email == null) return null;
            return userRepository.findByEmail(email).map(u -> u.getUserId()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
