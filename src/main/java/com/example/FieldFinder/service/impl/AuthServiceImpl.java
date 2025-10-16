package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.LoginOtp;
import com.example.FieldFinder.repository.OtpRepository;
import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.EmailService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final OtpRepository otpRepository;
    private final EmailService emailService;

    @Override
    @Transactional
    public void sendLoginOtp(String email) {

        otpRepository.deleteByEmail(email);

        String otp = String.format("%06d", new Random().nextInt(999999));

        otpRepository.save(new LoginOtp(email, otp, LocalDateTime.now().plusMinutes(5)));

        String subject = "Mã xác thực đăng nhập FieldFinder";
        String body = String.format("""
        Xin chào,

        Mã OTP đăng nhập của bạn là: %s
        Mã có hiệu lực trong 5 phút.

        Trân trọng,
        Đội ngũ FieldFinder
        """, otp);

        emailService.send(email, subject, body);
    }


    @Override
    @Transactional
    public boolean verifyOtp(String email, String code) {
        LoginOtp otp = otpRepository.findTopByEmailOrderByExpiryDesc(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTP not found"));


        if (!otp.getCode().equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP không đúng");
        }

        if (otp.getExpiry().isBefore(LocalDateTime.now())) {
            otpRepository.delete(otp);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP đã hết hạn");
        }

        otpRepository.delete(otp);
        return true;
    }
}
