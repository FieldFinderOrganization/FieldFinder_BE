package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.PasskeyLoginFinishRequestDTO;
import com.example.FieldFinder.dto.req.PasskeyLoginStartRequestDTO;
import com.example.FieldFinder.dto.req.PasskeyRegisterFinishRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyLoginStartResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyRegisterStartResponseDTO;
import com.example.FieldFinder.service.PasskeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/passkey")
@RequiredArgsConstructor
public class PasskeyController {

    private final PasskeyService passkeyService;

    // -------------------------------------------------------
    // ĐĂNG KÝ PASSKEY — Phải đăng nhập trước (protected)
    // -------------------------------------------------------

    /**
     * Bước 1: Bắt đầu đăng ký PassKey.
     * Yêu cầu user đã đăng nhập (bằng password/OTP/Google/Facebook).
     * Trả về challenge + RP info để client gọi WebAuthn API.
     */
    @PostMapping("/register/start")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PasskeyRegisterStartResponseDTO> registerStart(
            @AuthenticationPrincipal String userEmail) {
        PasskeyRegisterStartResponseDTO response = passkeyService.startRegistration(userEmail);
        return ResponseEntity.ok(response);
    }

    /**
     * Bước 2: Hoàn tất đăng ký PassKey.
     * Client gửi attestation từ authenticator (vân tay/Face ID).
     * Server verify và lưu credential vào DB.
     */
    @PostMapping("/register/finish")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> registerFinish(
            @AuthenticationPrincipal String userEmail,
            @RequestBody PasskeyRegisterFinishRequestDTO dto) {
        passkeyService.finishRegistration(userEmail, dto);
        return ResponseEntity.ok("PassKey đã được đăng ký thành công!");
    }

    // -------------------------------------------------------
    // ĐĂNG NHẬP BẰNG PASSKEY — Public (không cần token)
    // -------------------------------------------------------

    /**
     * Bước 1: Bắt đầu đăng nhập bằng PassKey.
     * Client gửi email, server trả về challenge + danh sách credentialId.
     */
    @PostMapping("/login/start")
    public ResponseEntity<PasskeyLoginStartResponseDTO> loginStart(
            @RequestBody PasskeyLoginStartRequestDTO dto) {
        PasskeyLoginStartResponseDTO response = passkeyService.startLogin(dto.getEmail());
        return ResponseEntity.ok(response);
    }

    /**
     * Bước 2: Hoàn tất đăng nhập bằng PassKey.
     * Client gửi assertion (chữ ký từ authenticator).
     * Server verify signature → trả về JWT nội bộ nếu hợp lệ.
     */
    @PostMapping("/login/finish")
    public ResponseEntity<AuthTokenResponseDTO> loginFinish(
            @RequestBody PasskeyLoginFinishRequestDTO dto) {
        AuthTokenResponseDTO tokenResponse = passkeyService.finishLogin(dto);
        return ResponseEntity.ok(tokenResponse);
    }
}
