package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Trả về cho client khi bắt đầu đăng ký PassKey.
 * Client dùng dữ liệu này để gọi WebAuthn API tạo credential.
 */
@Getter
@AllArgsConstructor
public class PasskeyRegisterStartResponseDTO {

    /** ID để tra challenge trong Redis khi finish */
    private String challengeId;

    /** Challenge bytes (base64url) — client ký bằng authenticator */
    private String challenge;

    /** Relying Party ID — phải match với domain của app (VD: "localhost", "yourdomain.com") */
    private String rpId;

    /** Tên hiển thị của RP (VD: "FieldFinder") */
    private String rpName;

    /** User ID base64url — dùng cho WebAuthn user entity */
    private String userId;

    /** Email — dùng làm userName trong WebAuthn */
    private String userName;

    /** Tên hiển thị — dùng làm userDisplayName trong WebAuthn */
    private String userDisplayName;
}
