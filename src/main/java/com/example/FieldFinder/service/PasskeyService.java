package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.PasskeyLoginFinishRequestDTO;
import com.example.FieldFinder.dto.req.PasskeyRegisterFinishRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyLoginStartResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyRegisterStartResponseDTO;

public interface PasskeyService {

    /** Bước 1 đăng ký: tạo challenge, trả về thông tin để client gọi WebAuthn API */
    PasskeyRegisterStartResponseDTO startRegistration(String userEmail);

    /** Bước 2 đăng ký: verify attestation, lưu credential vào DB */
    void finishRegistration(String userEmail, PasskeyRegisterFinishRequestDTO dto);

    /** Bước 1 đăng nhập: tạo challenge + danh sách credentialId của user */
    PasskeyLoginStartResponseDTO startLogin(String email);

    /** Bước 2 đăng nhập: verify assertion signature, trả về JWT nội bộ */
    AuthTokenResponseDTO finishLogin(PasskeyLoginFinishRequestDTO dto);
}
