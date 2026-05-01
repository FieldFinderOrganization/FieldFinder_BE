package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock EmailService emailService;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;

    @InjectMocks AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    class sendLoginOtp {
        @Test
        void storesOtpInRedisAndSendsEmail() {
            service.sendLoginOtp("user@example.com");

            verify(valueOps).set(eq("login_otp:user@example.com"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
            verify(emailService).send(eq("user@example.com"), anyString(), anyString());
        }

        @Test
        void generatedOtp_isSixDigits() {
            ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
            service.sendLoginOtp("user@example.com");

            verify(valueOps).set(anyString(), otpCaptor.capture(), anyLong(), any());
            String otp = otpCaptor.getValue();
            assertTrue(otp.matches("\\d{6}"), "OTP phải là 6 chữ số: " + otp);
        }
    }

    @Nested
    class sendResetPasswordOtp {
        @Test
        void storesOtpWithResetPrefixAndSendsEmail() {
            service.sendResetPasswordOtp("user@example.com");

            verify(valueOps).set(eq("reset_otp:user@example.com"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
            verify(emailService).send(eq("user@example.com"), anyString(), anyString());
        }
    }

    @Nested
    class verifyOtp {
        @Test
        void validLoginOtp_returnsTrue() {
            when(valueOps.get("login_otp:user@example.com")).thenReturn("123456");

            boolean result = service.verifyOtp("user@example.com", "123456");

            assertTrue(result);
            verify(redisTemplate).delete("login_otp:user@example.com");
        }

        @Test
        void validResetOtp_returnsTrue() {
            when(valueOps.get("login_otp:user@example.com")).thenReturn(null);
            when(valueOps.get("reset_otp:user@example.com")).thenReturn("654321");

            boolean result = service.verifyOtp("user@example.com", "654321");

            assertTrue(result);
            verify(redisTemplate).delete("reset_otp:user@example.com");
        }

        @Test
        void wrongOtp_ThrowsException() {
            when(valueOps.get("login_otp:user@example.com")).thenReturn("111111");
            when(valueOps.get("reset_otp:user@example.com")).thenReturn("222222");

            assertThrows(ResponseStatusException.class,
                    () -> service.verifyOtp("user@example.com", "999999"));
        }

        @Test
        void noOtpStored_ThrowsException() {
            when(valueOps.get("login_otp:user@example.com")).thenReturn(null);
            when(valueOps.get("reset_otp:user@example.com")).thenReturn(null);

            assertThrows(ResponseStatusException.class,
                    () -> service.verifyOtp("user@example.com", "000000"));
        }
    }

    @Nested
    class sendActivationEmail {
        @Test
        void sendsEmailToGivenAddress() {
            service.sendActivationEmail("new@example.com");

            verify(emailService).send(eq("new@example.com"), anyString(), anyString());
        }
    }
}