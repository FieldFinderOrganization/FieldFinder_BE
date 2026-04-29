package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserProvider.ProviderName;
import com.example.FieldFinder.service.JwtService;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final SocialLoginService socialLoginService;
    private final JwtService jwtService;

    @Value("${google.client-id}")
    private String googleClientId;

    private GoogleIdTokenVerifier verifier;

    @PostConstruct
    public void init() {
        verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance()
        )
                // Bắt buộc check audience == GOOGLE_CLIENT_ID của app
                // Chống token từ app Google khác bắn vào server này
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }

    /**
     * Verify Google ID Token và trả về JWT nội bộ.
     *
     * @param idToken Google ID Token từ mobile/web (Google Sign-In SDK)
     * @return cặp accessToken + refreshToken
     * @throws ResponseStatusException 401 nếu token không hợp lệ / sai audience / hết hạn
     */
    public AuthTokenResponseDTO login(String idToken) {
        GoogleIdToken.Payload payload = verifyToken(idToken);

        String googleUid     = payload.getSubject();                      // Google user ID
        String email         = payload.getEmail();
        boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());
        String name          = (String) payload.get("name");
        String picture       = (String) payload.get("picture");

        if (email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google account không có email. Vui lòng dùng tài khoản Google khác.");
        }

        // Account Linking: tìm hoặc tạo user, tự động link provider
        User user = socialLoginService.findOrCreateUser(
                ProviderName.GOOGLE,
                googleUid,
                email,
                emailVerified,
                name,
                picture
        );

        user.setLastLoginAt(new java.util.Date());
        return jwtService.generateTokenPair(user);
    }

    private GoogleIdToken.Payload verifyToken(String idTokenString) {
        try {
            GoogleIdToken googleIdToken = verifier.verify(idTokenString);

            if (googleIdToken == null) {
                // null = signature sai, audience không khớp, hoặc token hết hạn
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Google ID Token không hợp lệ, sai audience, hoặc đã hết hạn.");
            }

            return googleIdToken.getPayload();

        } catch (GeneralSecurityException e) {
            log.error("Lỗi bảo mật khi verify Google token", e);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Không thể xác thực Google token: " + e.getMessage());
        } catch (IOException e) {
            log.error("Lỗi kết nối khi verify Google token", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể kết nối đến Google để xác thực token. Vui lòng thử lại.");
        }
    }
}