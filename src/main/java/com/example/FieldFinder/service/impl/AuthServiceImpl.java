package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final EmailService emailService;

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String OTP_PREFIX = "login_otp:";

    @Override
    public void sendLoginOtp(String email) {

        String otp = String.format("%06d", new Random().nextInt(999999));

        String redisKey = OTP_PREFIX + email;


        redisTemplate.opsForValue().set(redisKey, otp, 5, TimeUnit.MINUTES);

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
    public boolean verifyOtp(String email, String code) {
        String redisKey = OTP_PREFIX + email;

        Object savedOtpObj = redisTemplate.opsForValue().get(redisKey);

        if (savedOtpObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTP is expired or not found");
        }

        String savedOtp = String.valueOf(savedOtpObj);

        if (!savedOtp.equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTP is not correct");
        }

        redisTemplate.delete(redisKey);

        return true;
    }

    @Override
    public void sendActivationEmail(String email) {
        String subject = "Chào mừng đến với FieldFinder!";
        String body = """
                Xin chào,

                Tài khoản FieldFinder của bạn đã được tạo thành công!
                Bạn có thể đăng nhập ngay bây giờ và bắt đầu khám phá.

                Trân trọng,
                Đội ngũ FieldFinder
                """;
        emailService.send(email, subject, body);
    }
}