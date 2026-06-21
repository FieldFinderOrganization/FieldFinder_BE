package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.PaymentSecurity;
import com.example.FieldFinder.repository.PaymentSecurityRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentPinServiceImplTest {

    @Mock PaymentSecurityRepository securityRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailService emailService;

    @InjectMocks PaymentPinServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    private void encoderStubs() {
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(i -> "H:" + i.getArgument(0));
        lenient().when(passwordEncoder.matches(anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(1).equals("H:" + i.getArgument(0)));
        lenient().when(securityRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private PaymentSecurity sec(String pinHash, int attempts) {
        return PaymentSecurity.builder().userId(userId).pinHash(pinHash).failedAttempts(attempts).build();
    }

    @Test
    void setPin_firstTime_hashes() {
        encoderStubs();
        when(securityRepository.findByUserId(userId)).thenReturn(Optional.empty());

        service.setPin(userId, "123456");
        // getOrCreate tạo rồi setPin set hash — không ném là đạt; format 6 số hợp lệ
    }

    @Test
    void setPin_invalidFormat_throws() {
        assertThatThrownBy(() -> service.setPin(userId, "12ab"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyPin_wrong_incrementsAttempts() {
        encoderStubs();
        PaymentSecurity s = sec("H:111111", 0);
        when(securityRepository.findByUserId(userId)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.verifyPin(userId, "000000"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(s.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void verifyPin_maxAttempts_locks() {
        encoderStubs();
        PaymentSecurity s = sec("H:111111", 4); // lần sai thứ 5 ⇒ khóa
        when(securityRepository.findByUserId(userId)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.verifyPin(userId, "000000"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
        assertThat(s.getLockedUntil()).isNotNull();
    }

    @Test
    void verifyPin_correct_resetsAttempts() {
        encoderStubs();
        PaymentSecurity s = sec("H:111111", 3);
        when(securityRepository.findByUserId(userId)).thenReturn(Optional.of(s));

        service.verifyPin(userId, "111111"); // đúng
        assertThat(s.getFailedAttempts()).isZero();
    }

    @Test
    void requireVerified_noPin_throws428() {
        when(securityRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireVerified(userId, "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }
}
