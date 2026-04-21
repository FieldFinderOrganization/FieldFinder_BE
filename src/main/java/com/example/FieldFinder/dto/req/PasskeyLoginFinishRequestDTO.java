package com.example.FieldFinder.dto.req;

import lombok.*;

/**
 * Client gửi lên sau khi authenticator đã ký challenge thành công.
 * Tất cả byte[] đều được encode base64url.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PasskeyLoginFinishRequestDTO {

    /** challengeId nhận được từ /login/start */
    private String challengeId;

    /** Credential ID mà authenticator dùng để ký (base64url) */
    private String credentialId;

    /** ClientDataJSON từ WebAuthn assertion response (base64url) */
    private String clientDataJSON;

    /** AuthenticatorData từ WebAuthn assertion response (base64url) */
    private String authenticatorData;

    /** Chữ ký của authenticator (base64url) */
    private String signature;

    /** UserHandle (optional — base64url) */
    private String userHandle;
}