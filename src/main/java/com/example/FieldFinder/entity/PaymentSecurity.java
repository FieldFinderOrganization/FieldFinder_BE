package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mã PIN thanh toán riêng (tách khỏi mật khẩu đăng nhập) — gác thao tác tiền (đổi TK nhận, rút).
 * PIN lưu HASH (BCrypt). Khóa tạm sau nhiều lần sai; reset qua OTP email.
 */
@Entity
@Table(
        name = "PaymentSecurity",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_security_user", columnNames = {"UserId"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSecurity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "Id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "UserId", nullable = false)
    private UUID userId;

    @Column(name = "PinHash", length = 100)
    private String pinHash;

    @Builder.Default
    @Column(name = "FailedAttempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "LockedUntil")
    private LocalDateTime lockedUntil;

    @Column(name = "ResetOtpHash", length = 100)
    private String resetOtpHash;

    @Column(name = "ResetOtpExpiry")
    private LocalDateTime resetOtpExpiry;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;
}
