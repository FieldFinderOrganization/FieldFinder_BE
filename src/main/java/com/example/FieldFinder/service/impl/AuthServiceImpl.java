package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
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

    private static final String LOGIN_OTP_PREFIX = "login_otp:";
    private static final String RESET_PASSWORD_OTP_PREFIX = "reset_otp:";

    @Override
    public void sendLoginOtp(String email) {
        generateAndSendOtp(email, LOGIN_OTP_PREFIX, "FieldFinder - Mã xác thực đăng nhập FieldFinder", "Mã OTP đăng nhập");
    }

    public void sendResetPasswordOtp(String email) {
        generateAndSendOtp(email, RESET_PASSWORD_OTP_PREFIX, "FieldFinder - Mã đặt lại mật khẩu", "Mã OTP để đặt lại mật khẩu");
    }

    private void generateAndSendOtp(String email, String prefix, String subject, String purpose) {
        String otp = String.format("%06d", new Random().nextInt(999999));
        String redisKey = prefix + email;

        redisTemplate.opsForValue().set(redisKey, otp, 5, TimeUnit.MINUTES);

        String body = String.format("""
        Xin chào,

        %s của bạn là: %s
        Mã có hiệu lực trong 5 phút.

        Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email này.

        Trân trọng,
        Đội ngũ FieldFinder
        """, purpose, otp);

        emailService.send(email, subject, body);
    }

    @Override
    public boolean verifyOtp(String email, String code) {
        if (checkAndVerify(email, code, LOGIN_OTP_PREFIX)) return true;
        if (checkAndVerify(email, code, RESET_PASSWORD_OTP_PREFIX)) return true;

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP không chính xác hoặc đã hết hạn");
    }

    private boolean checkAndVerify(String email, String code, String prefix) {
        String redisKey = prefix + email;
        Object savedOtpObj = redisTemplate.opsForValue().get(redisKey);
        if (savedOtpObj != null && String.valueOf(savedOtpObj).equals(code)) {
            redisTemplate.delete(redisKey);
            return true;
        }
        return false;
    }

    @Override
    public void sendActivationEmail(String email) {
        String subject = "FieldFinder - Chào mừng đến với chúng tôi!";
        String body = getBody();
        emailService.send(email, subject, body);
    }

    private static @NonNull String getBody() {
        return """
                Xin chào,
                
                Tài khoản FieldFinder của bạn đã được tạo thành công!
                Bạn có thể đăng nhập ngay bây giờ và bắt đầu khám phá.
                
                Trân trọng,
                Đội ngũ FieldFinder
                """;
    }
}