package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.PasswordResetToken;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.PasswordResetTokenRepository;
import com.example.FieldFinder.repository.UserProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailService emailService;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock SocialLoginService socialLoginService;
    @Mock UserProviderRepository userProviderRepository;

    UserServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository, passwordEncoder, emailService,
                passwordResetTokenRepository, redisTemplate, socialLoginService, userProviderRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setUserId(userId);
        user.setEmail("test@example.com");
        user.setName("Triet");
        user.setStatus(User.Status.ACTIVE);
    }

    @Nested
    class loginUser {
        @Test
        void activeUser_returnsDTO() {
            FirebaseToken token = mock(FirebaseToken.class);
            when(token.getEmail()).thenReturn("test@example.com");
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

            UserResponseDTO result = service.loginUser(token);

            assertNotNull(result);
            assertEquals("test@example.com", result.getEmail());
        }

        @Test
        void blockedUser_ThrowsException() {
            user.setStatus(User.Status.BLOCKED);
            FirebaseToken token = mock(FirebaseToken.class);
            when(token.getEmail()).thenReturn("test@example.com");
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

            assertThrows(ResponseStatusException.class, () -> service.loginUser(token));
        }

        @Test
        void userNotFound_ThrowsException() {
            FirebaseToken token = mock(FirebaseToken.class);
            when(token.getEmail()).thenReturn("unknown@example.com");
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.loginUser(token));
        }
    }

    @Nested
    class updateUser {
        @Test
        void valid_updatesAndReturnsDTO() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UserUpdateRequestDTO req = new UserUpdateRequestDTO();
            req.setName("New Name");
            req.setPhone("0901234567");

            UserResponseDTO result = service.updateUser(userId, req);

            assertNotNull(result);
            assertEquals("New Name", user.getName());
        }

        @Test
        void notFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            UserUpdateRequestDTO req = new UserUpdateRequestDTO();
            assertThrows(ResponseStatusException.class, () -> service.updateUser(userId, req));
        }

        @Test
        void duplicateEmail_ThrowsException() {
            user.setEmail("old@example.com");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

            UserUpdateRequestDTO req = new UserUpdateRequestDTO();
            req.setEmail("new@example.com");

            assertThrows(ResponseStatusException.class, () -> service.updateUser(userId, req));
        }
    }

    @Nested
    class getAllUsers {
        @Test
        void hasData_ReturnsList() {
            when(userRepository.findAll()).thenReturn(List.of(user));

            List<UserResponseDTO> result = service.getAllUsers();

            assertEquals(1, result.size());
        }

        @Test
        void empty_ReturnsEmpty() {
            when(userRepository.findAll()).thenReturn(List.of());

            assertTrue(service.getAllUsers().isEmpty());
        }
    }

    @Nested
    class updateUserStatus {
        @Test
        void validStatus_updatesUser() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UserResponseDTO result = service.updateUserStatus(userId, "BLOCKED");

            assertEquals(User.Status.BLOCKED, user.getStatus());
            assertNotNull(result);
        }

        @Test
        void invalidStatus_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateUserStatus(userId, "SUPERACTIVE"));
            assertTrue(ex.getMessage().contains("Invalid status"));
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                    () -> service.updateUserStatus(userId, "ACTIVE"));
        }
    }

    @Nested
    class sendPasswordResetEmail {
        @Test
        void existingUser_noActiveToken_createsNewToken() {
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordResetTokenRepository.findByUser(user)).thenReturn(Optional.empty());

            service.sendPasswordResetEmail("test@example.com");

            verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
            verify(emailService).send(eq("test@example.com"), anyString(), anyString());
        }

        @Test
        void existingValidToken_reusesToken() {
            PasswordResetToken existing = PasswordResetToken.builder()
                    .token("existing-token")
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusMinutes(20))
                    .build();
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordResetTokenRepository.findByUser(user)).thenReturn(Optional.of(existing));

            service.sendPasswordResetEmail("test@example.com");

            verify(passwordResetTokenRepository, never()).save(any());
            verify(emailService).send(anyString(), anyString(), contains("existing-token"));
        }

        @Test
        void emailNotFound_ThrowsException() {
            when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class,
                    () -> service.sendPasswordResetEmail("none@example.com"));
        }
    }

    @Nested
    class resetPassword {
        @Test
        void validToken_updatesPassword() {
            PasswordResetToken rt = PasswordResetToken.builder()
                    .token("tok")
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusMinutes(10))
                    .build();
            when(passwordResetTokenRepository.findByToken("tok")).thenReturn(Optional.of(rt));
            lenient().when(passwordEncoder.matches(anyString(), any())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");

            service.resetPassword("tok", "newPass123");

            assertEquals("encoded", user.getPassword());
            verify(passwordResetTokenRepository).delete(rt);
        }

        @Test
        void expiredToken_ThrowsException() {
            PasswordResetToken rt = PasswordResetToken.builder()
                    .token("expired")
                    .user(user)
                    .expiryDate(LocalDateTime.now().minusMinutes(1))
                    .build();
            when(passwordResetTokenRepository.findByToken("expired")).thenReturn(Optional.of(rt));

            assertThrows(ResponseStatusException.class,
                    () -> service.resetPassword("expired", "newPass"));
        }

        @Test
        void samePassword_ThrowsException() {
            user.setPassword("encoded-old");
            PasswordResetToken rt = PasswordResetToken.builder()
                    .token("tok")
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusMinutes(10))
                    .build();
            when(passwordResetTokenRepository.findByToken("tok")).thenReturn(Optional.of(rt));
            when(passwordEncoder.matches("samePass", "encoded-old")).thenReturn(true);

            assertThrows(ResponseStatusException.class,
                    () -> service.resetPassword("tok", "samePass"));
        }

        @Test
        void invalidToken_ThrowsException() {
            when(passwordResetTokenRepository.findByToken("bad")).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class,
                    () -> service.resetPassword("bad", "anything"));
        }
    }

    @Nested
    class verifyCurrentPassword {
        @Test
        void passwordMatches_returnsTrue() {
            user.setPassword("encoded");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("raw", "encoded")).thenReturn(true);

            assertTrue(service.verifyCurrentPassword(userId, "raw"));
        }

        @Test
        void passwordNotMatches_returnsFalse() {
            user.setPassword("encoded");
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

            assertFalse(service.verifyCurrentPassword(userId, "wrong"));
        }

        @Test
        void noPassword_returnsFalse() {
            user.setPassword(null);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertFalse(service.verifyCurrentPassword(userId, "anything"));
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class,
                    () -> service.verifyCurrentPassword(userId, "pass"));
        }
    }

    @Nested
    class sessionManagement {
        @Test
        void registerAndGet_returnsUserId() {
            service.registerUserSession("sess-1", userId);

            assertEquals(userId, service.getUserIdBySession("sess-1"));
        }

        @Test
        void remove_sessionGone() {
            service.registerUserSession("sess-2", userId);
            service.removeUserSession("sess-2");

            assertNull(service.getUserIdBySession("sess-2"));
        }

        @Test
        void getUnknownSession_returnsNull() {
            assertNull(service.getUserIdBySession("no-such-session"));
        }
    }

    @Nested
    class getUserById {
        @Test
        void hasData_ReturnsDTO() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UserResponseDTO result = service.getUserById(userId);

            assertNotNull(result);
            assertEquals("test@example.com", result.getEmail());
        }

        @Test
        void notFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.getUserById(userId));
        }
    }
}