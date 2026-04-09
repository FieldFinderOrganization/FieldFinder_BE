package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Trả về cho client khi bắt đầu đăng nhập PassKey.
 * Client dùng dữ liệu này để gọi WebAuthn API ký challenge.
 */
@Getter
@AllArgsConstructor
public class PasskeyLoginStartResponseDTO {

    /** ID để tra challenge trong Redis khi finish */
    private String challengeId;

    /** Challenge bytes (base64url) — client dùng authenticator để ký */
    private String challenge;

    /** Relying Party ID */
    private String rpId;

    /**
     * Danh sách credentialId (base64url) của user.
     * Authenticator sẽ chọn đúng key tương ứng để ký.
     */
    private List<String> allowCredentials;
}
