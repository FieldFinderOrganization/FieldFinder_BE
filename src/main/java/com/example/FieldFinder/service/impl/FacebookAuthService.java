package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserProvider.ProviderName;
import com.example.FieldFinder.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class FacebookAuthService {

    private final SocialLoginService socialLoginService;
    private final JwtService jwtService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${facebook.app-id}")
    private String appId;

    @Value("${facebook.app-secret}")
    private String appSecret;

    /**
     * Verify Facebook Access Token và trả về JWT nội bộ.
     *
     * <p>Flow:
     * <ol>
     *   <li>Gọi debug_token API → kiểm tra token hợp lệ + app_id đúng (chống token từ app khác)</li>
     *   <li>Gọi /me API → lấy id, name, email, picture</li>
     *   <li>Account Linking qua SocialLoginService</li>
     *   <li>Trả về JWT nội bộ</li>
     * </ol>
     */
    public AuthTokenResponseDTO login(String accessToken) {
        // Bước 1: Verify token — check app_id và is_valid
        verifyToken(accessToken);

        // Bước 2: Lấy thông tin user từ Graph API
        FacebookUserInfo info = fetchUserInfo(accessToken);

        if (info.email() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Facebook account không có email. " +
                            "Vui lòng cấp quyền email trong cài đặt Facebook hoặc dùng tài khoản khác.");
        }

        // Bước 3: Account Linking
        // Facebook email trả về qua Graph API đã được verify bởi Facebook
        User user = socialLoginService.findOrCreateUser(
                ProviderName.FACEBOOK,
                info.facebookId(),
                info.email(),
                true,   // Facebook email mặc định đã verified
                info.name(),
                info.pictureUrl()
        );

        user.setLastLoginAt(new java.util.Date());
        return jwtService.generateTokenPair(user);
    }

    /**
     * Gọi Facebook debug_token API để xác minh token hợp lệ và thuộc đúng app.
     * Bắt buộc check app_id để chống token từ app Facebook khác.
     */
    private void verifyToken(String accessToken) {
        String appToken = appId + "|" + appSecret;
        String debugUrl = String.format(
                "https://graph.facebook.com/debug_token?input_token=%s&access_token=%s",
                accessToken, appToken
        );

        try {
            String response = restTemplate.getForObject(debugUrl, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");

            boolean isValid = data.path("is_valid").asBoolean(false);
            String returnedAppId = data.path("app_id").asText("");

            if (!isValid) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Facebook Access Token không hợp lệ hoặc đã hết hạn.");
            }

            // Bắt buộc: check app_id khớp với app của mình
            if (!appId.equals(returnedAppId)) {
                log.warn("Facebook token app_id mismatch: expected={}, got={}", appId, returnedAppId);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Facebook Access Token không thuộc ứng dụng này.");
            }

        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Lỗi kết nối khi gọi Facebook debug_token API", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể kết nối đến Facebook để xác thực token. Vui lòng thử lại.");
        } catch (Exception e) {
            log.error("Lỗi khi parse Facebook debug_token response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi xử lý phản hồi từ Facebook.");
        }
    }

    /**
     * Gọi Facebook Graph API /me để lấy thông tin user.
     */
    private FacebookUserInfo fetchUserInfo(String accessToken) {
        String meUrl = String.format(
                "https://graph.facebook.com/me?fields=id,name,email,picture.type(large)&access_token=%s",
                accessToken
        );

        try {
            String response = restTemplate.getForObject(meUrl, String.class);
            JsonNode root = objectMapper.readTree(response);

            String facebookId = root.path("id").asText(null);
            String name       = root.path("name").asText(null);
            String email      = root.path("email").asText(null);  // null nếu user không cấp quyền
            String pictureUrl = root.path("picture").path("data").path("url").asText(null);

            if (facebookId == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Không thể lấy thông tin từ Facebook. Token có thể đã hết hạn.");
            }

            return new FacebookUserInfo(facebookId, name, email, pictureUrl);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Lỗi kết nối khi gọi Facebook /me API", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể kết nối đến Facebook. Vui lòng thử lại.");
        } catch (Exception e) {
            log.error("Lỗi khi parse Facebook /me response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi xử lý thông tin người dùng từ Facebook.");
        }
    }

    private record FacebookUserInfo(
            String facebookId,
            String name,
            String email,
            String pictureUrl
    ) {}
}