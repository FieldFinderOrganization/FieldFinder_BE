package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.entity.RefreshToken;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtServiceImplTest {

    @Mock RefreshTokenRepository refreshTokenRepository;

    JwtServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new JwtServiceImpl(refreshTokenRepository);
        // 32+ chars for HS256
        ReflectionTestUtils.setField(service, "jwtSecret",
                "fieldfinder-super-secret-key-1234567890abcdef");
        ReflectionTestUtils.setField(service, "accessTokenTtlSeconds", 1800L);
        ReflectionTestUtils.setField(service, "refreshTokenTtlSeconds", 2592000L);

        user = new User();
        user.setUserId(UUID.randomUUID());
        user.setEmail("test@example.com");
        user.setRole(User.Role.USER);
    }

    @Nested
    class generateAccessToken {
        @Test
        void returnsNonNullToken() {
            String token = service.generateAccessToken(user);

            assertNotNull(token);
            assertFalse(token.isBlank());
        }

        @Test
        void tokenContainsCorrectClaims() {
            String token = service.generateAccessToken(user);
            Claims claims = service.verifyAccessToken(token);

            assertEquals(user.getEmail(), claims.getSubject());
            assertEquals(user.getUserId().toString(), claims.get("userId", String.class));
            assertEquals("USER", claims.get("role", String.class));
        }
    }

    @Nested
    class generateRefreshToken {
        @Test
        void deletesOldTokenAndSavesNew() {
            String raw = service.generateRefreshToken(user);

            assertNotNull(raw);
            verify(refreshTokenRepository).deleteByUser(user);
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        void savedTokenHash_isSha256NotRaw() {
            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            String raw = service.generateRefreshToken(user);

            verify(refreshTokenRepository).save(captor.capture());
            assertNotEquals(raw, captor.getValue().getTokenHash());
        }
    }

    @Nested
    class verifyAccessToken {
        @Test
        void validToken_returnsClaims() {
            String token = service.generateAccessToken(user);

            Claims claims = service.verifyAccessToken(token);

            assertNotNull(claims);
            assertEquals(user.getEmail(), claims.getSubject());
        }

        @Test
        void invalidToken_ThrowsException() {
            assertThrows(Exception.class, () -> service.verifyAccessToken("not.a.jwt"));
        }
    }

    @Nested
    class refreshAccessToken {
        @Test
        void validToken_returnsNewAccessToken() {
            String rawToken = "some-raw-token";
            RefreshToken stored = RefreshToken.builder()
                    .user(user)
                    .tokenHash("irrelevant") // hash matched by mock
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

            String newAccess = service.refreshAccessToken(rawToken);

            assertNotNull(newAccess);
            assertFalse(newAccess.isBlank());
        }

        @Test
        void expiredToken_ThrowsException() {
            RefreshToken expired = RefreshToken.builder()
                    .user(user)
                    .tokenHash("hash")
                    .expiresAt(LocalDateTime.now().minusSeconds(1))
                    .build();
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

            assertThrows(ResponseStatusException.class,
                    () -> service.refreshAccessToken("old-token"));
            verify(refreshTokenRepository).delete(expired);
        }

        @Test
        void notFound_ThrowsException() {
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class,
                    () -> service.refreshAccessToken("unknown"));
        }
    }

    @Nested
    class revokeRefreshToken {
        @Test
        void found_deletes() {
            RefreshToken stored = RefreshToken.builder().user(user).tokenHash("h").build();
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

            service.revokeRefreshToken("raw");

            verify(refreshTokenRepository).delete(stored);
        }

        @Test
        void notFound_noOp() {
            when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

            service.revokeRefreshToken("unknown");

            verify(refreshTokenRepository, never()).delete(any(RefreshToken.class));
        }
    }

    @Nested
    class generateTokenPair {
        @Test
        void returnsBothTokens() {
            AuthTokenResponseDTO result = service.generateTokenPair(user);

            assertNotNull(result.getAccessToken());
            assertNotNull(result.getRefreshToken());
            assertNotNull(result.getUser());
            assertEquals(user.getEmail(), result.getUser().getEmail());
        }
    }
}