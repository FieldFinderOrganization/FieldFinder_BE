package com.example.FieldFinder.dto.req;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasskeyRegisterFinishRequestDTO {

    /** challengeId nhận được từ /register/start */
    private String challengeId;

    /** Credential ID do authenticator tạo ra (base64url) */
    private String credentialId;

    /** ClientDataJSON từ WebAuthn response (base64url) */
    private String clientDataJSON;

    /** AttestationObject từ WebAuthn response (base64url) */
    private String attestationObject;

    /** Tên thiết bị do user đặt — VD: "iPhone 15 Pro", "Laptop" (optional) */
    private String displayName;
}
