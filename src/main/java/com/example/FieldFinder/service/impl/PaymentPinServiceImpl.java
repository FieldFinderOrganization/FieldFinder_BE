package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.PaymentSecurity;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.PaymentSecurityRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.PaymentPinService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentPinServiceImpl implements PaymentPinService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 30;
    private static final long OTP_TTL_MINUTES = 10;
    private static final SecureRandom RNG = new SecureRandom();

    private final PaymentSecurityRepository securityRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Override
    public PinStatus status(UUID userId) {
        return securityRepository.findByUserId(userId)
                .map(s -> new PinStatus(s.getPinHash() != null, isLocked(s), s.getLockedUntil()))
                .orElse(new PinStatus(false, false, null));
    }

    @Override
    public boolean hasPin(UUID userId) {
        return securityRepository.findByUserId(userId).map(s -> s.getPinHash() != null).orElse(false);
    }

    @Override
    @Transactional
    public void setPin(UUID userId, String pin) {
        validatePinFormat(pin);
        PaymentSecurity sec = getOrCreate(userId);
        if (sec.getPinHash() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đã có PIN — dùng chức năng đổi PIN.");
        }
        sec.setPinHash(passwordEncoder.encode(pin));
        sec.setFailedAttempts(0);
        sec.setLockedUntil(null);
        sec.setUpdatedAt(LocalDateTime.now());
        securityRepository.save(sec);
    }

    @Override
    @Transactional
    public void changePin(UUID userId, String currentPin, String newPin) {
        verifyPin(userId, currentPin);
        validatePinFormat(newPin);
        PaymentSecurity sec = getOrCreate(userId);
        sec.setPinHash(passwordEncoder.encode(newPin));
        sec.setFailedAttempts(0);
        sec.setLockedUntil(null);
        sec.setUpdatedAt(LocalDateTime.now());
        securityRepository.save(sec);
    }

    @Override
    @Transactional
    public void verifyPin(UUID userId, String pin) {
        PaymentSecurity sec = securityRepository.findByUserId(userId)
                .filter(s -> s.getPinHash() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "PIN_REQUIRED"));
        if (isLocked(sec)) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "PIN bị khóa do nhập sai nhiều lần. Thử lại sau hoặc đặt lại PIN.");
        }
        if (pin != null && passwordEncoder.matches(pin, sec.getPinHash())) {
            if (sec.getFailedAttempts() != 0 || sec.getLockedUntil() != null) {
                sec.setFailedAttempts(0);
                sec.setLockedUntil(null);
                securityRepository.save(sec);
            }
            return;
        }
        int attempts = sec.getFailedAttempts() + 1;
        sec.setFailedAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            sec.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
            sec.setFailedAttempts(0);
            securityRepository.save(sec);
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Sai PIN quá " + MAX_ATTEMPTS + " lần — đã khóa " + LOCK_MINUTES + " phút.");
        }
        securityRepository.save(sec);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Sai mã PIN (còn " + (MAX_ATTEMPTS - attempts) + " lần).");
    }

    @Override
    public void requireVerified(UUID userId, String pin) {
        if (!hasPin(userId)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "PIN_REQUIRED");
        }
        verifyPin(userId, pin);
    }

    @Override
    @Transactional
    public void requestReset(UUID userId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng!"));
        PaymentSecurity sec = getOrCreate(userId);
        String otp = String.format("%06d", RNG.nextInt(1_000_000));
        sec.setResetOtpHash(passwordEncoder.encode(otp));
        sec.setResetOtpExpiry(LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES));
        sec.setUpdatedAt(LocalDateTime.now());
        securityRepository.save(sec);
        if (user.getEmail() != null) {
            try {
                emailService.send(user.getEmail(), "Mã đặt lại PIN thanh toán",
                        "Mã OTP đặt lại PIN của bạn là: " + otp + " (hết hạn sau " + OTP_TTL_MINUTES
                                + " phút). Không chia sẻ mã này cho bất kỳ ai.");
            } catch (Exception e) {
                System.err.println("Lỗi gửi OTP reset PIN: " + e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void resetPin(UUID userId, String otp, String newPin) {
        validatePinFormat(newPin);
        PaymentSecurity sec = securityRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chưa yêu cầu đặt lại PIN."));
        if (sec.getResetOtpHash() == null || sec.getResetOtpExpiry() == null
                || sec.getResetOtpExpiry().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP đã hết hạn — yêu cầu lại.");
        }
        if (otp == null || !passwordEncoder.matches(otp, sec.getResetOtpHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP không đúng.");
        }
        sec.setPinHash(passwordEncoder.encode(newPin));
        sec.setResetOtpHash(null);
        sec.setResetOtpExpiry(null);
        sec.setFailedAttempts(0);
        sec.setLockedUntil(null);
        sec.setUpdatedAt(LocalDateTime.now());
        securityRepository.save(sec);
    }

    private boolean isLocked(PaymentSecurity sec) {
        return sec.getLockedUntil() != null && sec.getLockedUntil().isAfter(LocalDateTime.now());
    }

    private PaymentSecurity getOrCreate(UUID userId) {
        return securityRepository.findByUserId(userId)
                .orElseGet(() -> securityRepository.save(PaymentSecurity.builder()
                        .userId(userId)
                        .createdAt(LocalDateTime.now())
                        .build()));
    }

    private static void validatePinFormat(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN phải gồm đúng 6 chữ số.");
        }
    }
}
