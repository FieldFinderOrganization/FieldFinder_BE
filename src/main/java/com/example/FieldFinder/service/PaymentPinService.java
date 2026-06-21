package com.example.FieldFinder.service;

import java.time.LocalDateTime;
import java.util.UUID;

/** Mã PIN thanh toán: đặt/đổi/xác thực (có khóa) + reset qua OTP email. Gác thao tác tiền. */
public interface PaymentPinService {

    record PinStatus(boolean hasPin, boolean locked, LocalDateTime lockedUntil) {}

    PinStatus status(UUID userId);

    boolean hasPin(UUID userId);

    /** Đặt PIN lần đầu (chưa có PIN). */
    void setPin(UUID userId, String pin);

    /** Đổi PIN (cần PIN hiện tại). */
    void changePin(UUID userId, String currentPin, String newPin);

    /** Xác thực PIN; sai nhiều lần ⇒ khóa tạm. Ném ResponseStatusException nếu sai/khóa. */
    void verifyPin(UUID userId, String pin);

    /** Gate thao tác tiền: chưa có PIN ⇒ 428 PIN_REQUIRED; có PIN ⇒ xác thực. */
    void requireVerified(UUID userId, String pin);

    /** Gửi OTP reset PIN về email. */
    void requestReset(UUID userId);

    /** Đặt lại PIN bằng OTP. */
    void resetPin(UUID userId, String otp, String newPin);
}
