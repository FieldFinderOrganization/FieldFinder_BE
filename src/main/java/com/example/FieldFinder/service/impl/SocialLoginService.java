package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserProvider;
import com.example.FieldFinder.entity.UserProvider.ProviderName;
import com.example.FieldFinder.repository.UserProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Xử lý logic Account Linking an toàn cho tất cả Social Provider.
 *
 * <p><b>Chống Account Hijacking:</b> Chỉ link tự động khi {@code emailVerified = true}.
 * Nếu email chưa verify → từ chối, tránh hacker tạo tài khoản trước với email nạn nhân.
 */
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final UserRepository userRepository;
    private final UserProviderRepository userProviderRepository;

    /**
     * Tìm hoặc tạo User từ thông tin Social Provider.
     *
     * <p>Logic:
     * <ol>
     *   <li>Tìm {@link UserProvider} theo (providerName, providerUid) → nếu có, login thẳng.</li>
     *   <li>Tìm User theo email:
     *       <ul>
     *         <li>Nếu có AND emailVerified → link provider mới vào user hiện tại.</li>
     *         <li>Nếu có AND NOT emailVerified → từ chối (Account Hijacking risk).</li>
     *       </ul>
     *   </li>
     *   <li>Nếu không có email → tạo User mới + UserProvider.</li>
     * </ol>
     *
     * @param providerName  GOOGLE | FACEBOOK | FIREBASE
     * @param providerUid   UID từ provider
     * @param email         Email từ provider
     * @param emailVerified Cờ provider xác nhận email đã được verify
     * @param name          Tên hiển thị
     * @param imageUrl      Avatar URL (nullable)
     * @return User entity đã được lưu/tìm
     */
    @Transactional
    public User findOrCreateUser(
            ProviderName providerName,
            String providerUid,
            String email,
            boolean emailVerified,
            String name,
            String imageUrl) {

        // Bước 1: Tìm provider record trước — đây là trường hợp phổ biến nhất (đã từng login)
        UserProvider existingProvider = userProviderRepository
                .findByProviderNameAndProviderUid(providerName, providerUid)
                .orElse(null);

        if (existingProvider != null) {
            User user = existingProvider.getUser();
            updateImageIfMissing(user, imageUrl);
            checkNotBlocked(user);
            return user;
        }

        // Bước 2: Tìm theo email
        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null) {
            // Account Hijacking guard
            if (!emailVerified) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Email này đã tồn tại nhưng chưa được xác thực bởi provider. " +
                                "Vui lòng đăng nhập bằng phương thức đăng ký ban đầu.");
            }

            checkNotBlocked(user);

            // Link provider mới vào user hiện tại
            linkProvider(user, providerName, providerUid);
            updateImageIfMissing(user, imageUrl);
            return user;
        }

        // Bước 3: Tạo user mới
        user = User.builder()
                .email(email)
                .name(name != null && !name.isBlank() ? name : email)
                .password(null)  // social-only user không có password
                .role(User.Role.USER)
                .status(User.Status.ACTIVE)
                .imageUrl(imageUrl)
                .build();

        user = userRepository.save(user);
        linkProvider(user, providerName, providerUid);

        return user;
    }

    private void linkProvider(User user, ProviderName providerName, String providerUid) {
        UserProvider provider = UserProvider.builder()
                .user(user)
                .providerName(providerName)
                .providerUid(providerUid)
                .linkedAt(LocalDateTime.now())
                .build();
        userProviderRepository.save(provider);
    }

    private void updateImageIfMissing(User user, String imageUrl) {
        if (user.getImageUrl() == null && imageUrl != null) {
            user.setImageUrl(imageUrl);
            userRepository.save(user);
        }
    }

    private void checkNotBlocked(User user) {
        if (user.getStatus() == User.Status.BLOCKED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ admin!");
        }
    }
}
