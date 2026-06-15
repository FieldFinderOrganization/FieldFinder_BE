package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.PasswordResetToken;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserProvider;
import com.example.FieldFinder.entity.UserProvider.ProviderName;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.PasswordResetTokenRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.UserProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.GeocodingService;
import com.example.FieldFinder.service.UserService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserProviderRepository userProviderRepository;
    private final SocialLoginService socialLoginService;

    private final Map<String, UUID> sessionUserMap = new ConcurrentHashMap<>();
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private GeocodingService geocodingService;

    @Autowired
    private com.example.FieldFinder.service.DiscountService discountService;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, EmailService emailService,
                           PasswordResetTokenRepository passwordResetTokenRepository, RedisTemplate<String, Object> redisTemplate,
                           SocialLoginService socialLoginService, UserProviderRepository userProviderRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.redisTemplate = redisTemplate;
        this.socialLoginService = socialLoginService;
        this.userProviderRepository = userProviderRepository;
    }

    @Override
    @Transactional
    public UserResponseDTO createUser(UserRequestDTO userRequestDTO) {
        if (userRepository.existsByEmail(userRequestDTO.getEmail())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Email already exists. Please use a different email!");
        }

        try {
            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(userRequestDTO.getEmail())
                    .setPassword(userRequestDTO.getPassword())
                    .setEmailVerified(false)
                    .setDisabled(false);

            UserRecord firebaseUser = FirebaseAuth.getInstance().createUser(request);

            String link = FirebaseAuth.getInstance().generateEmailVerificationLink(userRequestDTO.getEmail());

            emailService.send(
                    userRequestDTO.getEmail(),
                    "Verify your FieldFinder account",
                    "Click this link to verify your account: " + link);

            String encodedPassword = passwordEncoder.encode(userRequestDTO.getPassword());

            User user = userRequestDTO.toEntity(encodedPassword);
            User savedUser = userRepository.save(user);

            userProviderRepository.save(UserProvider.builder()
                    .user(savedUser)
                    .providerName(ProviderName.FIREBASE)
                    .providerUid(firebaseUser.getUid())
                    .linkedAt(java.time.LocalDateTime.now())
                    .build());

            // User mới nhận mọi mã public đang ACTIVE còn hạn vào ví
            discountService.grantWelcomeVouchers(savedUser.getUserId());

            return UserResponseDTO.toDto(savedUser);

        } catch (FirebaseAuthException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create user in Firebase: " + e.getMessage());
        }
    }

    @Override
    public UserResponseDTO loginUser(FirebaseToken decodedToken) {
        String email = decodedToken.getEmail();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User not found. Please check your email or register!"));

        if (user.getStatus() == User.Status.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account has been blocked. Please contact admin!");
        }

        return UserResponseDTO.toDto(user);
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID userId, UserUpdateRequestDTO userUpdateRequestDTO) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        if (userUpdateRequestDTO.getEmail() != null &&
                !user.getEmail().equals(userUpdateRequestDTO.getEmail()) &&
                userRepository.existsByEmail(userUpdateRequestDTO.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email already exists. Please use a different email!");
        }

        if (userUpdateRequestDTO.getName() != null) user.setName(userUpdateRequestDTO.getName());
        if (userUpdateRequestDTO.getEmail() != null) user.setEmail(userUpdateRequestDTO.getEmail());
        if (userUpdateRequestDTO.getPhone() != null) user.setPhone(userUpdateRequestDTO.getPhone());
        if (userUpdateRequestDTO.getStatus() != null) user.setStatus(userUpdateRequestDTO.getStatus());

        if (userUpdateRequestDTO.getImageUrl() != null) {
            user.setImageUrl(userUpdateRequestDTO.getImageUrl());
        }

        if (userUpdateRequestDTO.getDateOfBirth() != null) user.setDateOfBirth(userUpdateRequestDTO.getDateOfBirth());
        if (userUpdateRequestDTO.getGender() != null) user.setGender(userUpdateRequestDTO.getGender());
        boolean addressChanged = false;
        if (userUpdateRequestDTO.getAddress() != null
                && !userUpdateRequestDTO.getAddress().equals(user.getAddress())) {
            user.setAddress(userUpdateRequestDTO.getAddress());
            addressChanged = true;
        }
        if (userUpdateRequestDTO.getLatitude() != null) user.setLatitude(userUpdateRequestDTO.getLatitude());
        if (userUpdateRequestDTO.getLongitude() != null) user.setLongitude(userUpdateRequestDTO.getLongitude());
        if (addressChanged && userUpdateRequestDTO.getLatitude() == null) {
            user.setLatitude(null);
            user.setLongitude(null);
        }
        if (userUpdateRequestDTO.getProvince() != null) user.setProvince(userUpdateRequestDTO.getProvince());
        if (userUpdateRequestDTO.getDistrict() != null) user.setDistrict(userUpdateRequestDTO.getDistrict());
        if (userUpdateRequestDTO.getOccupation() != null) user.setOccupation(userUpdateRequestDTO.getOccupation());
        if (userUpdateRequestDTO.getPreferredPitchType() != null) user.setPreferredPitchType(userUpdateRequestDTO.getPreferredPitchType());
        if (userUpdateRequestDTO.getPreferredPlayTime() != null) user.setPreferredPlayTime(userUpdateRequestDTO.getPreferredPlayTime());

        // Shipper-only: toggle online + thông tin xe (PATCH một phần — chỉ set khi gửi).
        if (userUpdateRequestDTO.getAvailable() != null) user.setAvailable(userUpdateRequestDTO.getAvailable());
        if (userUpdateRequestDTO.getVehicleType() != null) user.setVehicleType(userUpdateRequestDTO.getVehicleType());
        if (userUpdateRequestDTO.getVehiclePlate() != null) user.setVehiclePlate(userUpdateRequestDTO.getVehiclePlate());

        User updatedUser = userRepository.save(user);
        if (addressChanged && updatedUser.getLatitude() == null) {
            geocodingService.geocodeAsync(updatedUser.getAddress()).thenAccept(opt -> opt.ifPresent(latLng -> {
                updatedUser.setLatitude(latLng.latitude());
                updatedUser.setLongitude(latLng.longitude());
                userRepository.save(updatedUser);
            }));
        }
        return UserResponseDTO.toDto(updatedUser);
    }

    @Override
    public List<UserResponseDTO> getAllUsers() {
        List<User> users = userRepository.findAll();
        return users.stream().map(UserResponseDTO::toDto).collect(Collectors.toList());
    }

    @Override
    public UserResponseDTO updateUserStatus(UUID userId, String statusStr) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found!"));

        try {
            User.Status newStatus = User.Status.valueOf(statusStr.toUpperCase());
            user.setStatus(newStatus);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status. Allowed: ACTIVE, BLOCKED!");
        }

        userRepository.save(user);
        return UserResponseDTO.toDto(user);
    }

    @Override
    @Transactional
    public void sendPasswordResetEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email not found!"));

        // Check for an existing token
        PasswordResetToken existingToken = passwordResetTokenRepository.findByUser(user)
                .orElse(null);

        String token;
        if (existingToken != null && existingToken.getExpiryDate().isAfter(LocalDateTime.now())) {
            // Reuse the existing valid token
            token = existingToken.getToken();
        } else {
            // Delete the old token if it exists
            if (existingToken != null) {
                passwordResetTokenRepository.delete(existingToken);
            }
            // Generate and save a new token
            token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .token(token)
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusMinutes(30))
                    .build();
            passwordResetTokenRepository.save(resetToken);
        }

        String resetLink = "http://localhost:8080/api/users/reset-password?token=" + token;

        emailService.send(
                email,
                "FieldFinder - Yêu cầu đặt lại mật khẩu",
                String.format("""
                        Xin chào,

                        Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản FieldFinder của bạn.

                        Nhấn vào liên kết bên dưới để đặt lại mật khẩu:
                        %s

                        Liên kết này có hiệu lực trong 30 phút.

                        Nếu bạn không thực hiện yêu cầu này, hãy bỏ qua email này. Tài khoản của bạn vẫn an toàn.

                        Trân trọng,
                        Đội ngũ FieldFinder
                        """, resetLink));
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid token"));

        if (resetToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
        }

        User user = resetToken.getUser();
        if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới không được trùng với mật khẩu cũ.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        passwordResetTokenRepository.delete(resetToken); // invalidate token
    }

    @Override
    public UserResponseDTO loginWithFirebase(FirebaseToken decodedToken) {
        // email_verified từ Firebase — gần như luôn true với Google, dùng cho Account Linking guard
        boolean emailVerified = Boolean.TRUE.equals(decodedToken.getClaims().get("email_verified"));

        User user = socialLoginService.findOrCreateUser(
                ProviderName.FIREBASE,
                decodedToken.getUid(),
                decodedToken.getEmail(),
                emailVerified,
                decodedToken.getName(),
                decodedToken.getPicture()
        );

        return UserResponseDTO.toDto(user);
    }

    @Override
    public void registerUserSession(String sessionId, UUID userId) {
        if (userId != null && sessionId != null) {
            sessionUserMap.put(sessionId, userId);
            System.out.println("✅ Registered Session: " + sessionId + " -> User: " + userId);
        }
    }

    @Override
    public UUID getUserIdBySession(String sessionId) {
        return sessionUserMap.get(sessionId);
    }

    @Override
    public void removeUserSession(String sessionId) {
        if (sessionId != null) {
            sessionUserMap.remove(sessionId);
        }
    }

    @Override
    public UserResponseDTO getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return UserResponseDTO.toDto(user);
    }

    @Override
    public void blockUser(String email) {
        User user = userRepository.findByEmail(email).get();
        user.setStatus(User.Status.BLOCKED);
        userRepository.save(user);

        redisTemplate.opsForValue().set("BANNED_USER: " + email, "true");
    }

    @Override
    public void resetPasswordWithOtp(String email, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email không tồn tại."));
        if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới không được trùng với mật khẩu cũ.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        String timeStr = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
                .format(java.time.LocalDateTime.now());

        emailService.send(
                email,
                "[FieldFinder] Thay đổi mật khẩu thành công",
                String.format("""
                        Xin chào,
                        
                        Bạn đã thay đổi mật khẩu thành công vào lúc %s.
                        
                        Nếu bạn là người thực hiện yêu cầu này, vui lòng bỏ qua email này.
                        Nếu không, hãy liên hệ ngay với bộ phận hỗ trợ của chúng tôi để được trợ giúp bảo mật tài khoản.
                        
                        Trân trọng,
                        Đội ngũ FieldFinder
                        """, timeStr)
        );
    }

    @Override
    public boolean verifyCurrentPassword(UUID userId, String currentPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại."));

        if (user.getPassword() == null) {
            return false;
        }

        return passwordEncoder.matches(currentPassword, user.getPassword());
    }

    @Override
    public List<String> getUserTopBrands(UUID userId, int limit) {
        if (userId == null || mongoTemplate == null) return List.of();

        try {
            // Query last 30 PRODUCT events from user
            Query q = Query.query(
                    Criteria.where("userId").is(userId.toString())
                            .and("eventType").in("VIEW_PRODUCT", "ADD_TO_CART", "CREATE_ORDER", "CHAT_RESULT_CLICK")
                            .and("itemType").is("PRODUCT")
            ).with(Sort.by(Sort.Direction.DESC, "timestamp"));
            q.limit(30);

            List<InteractionLog> events = mongoTemplate.find(q, InteractionLog.class);
            if (events.isEmpty()) return List.of();

            // Extract product IDs from events
            Set<Long> pids = events.stream()
                    .map(e -> {
                        try { return Long.parseLong(e.getItemId()); }
                        catch (Exception ex) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (pids.isEmpty()) return List.of();

            // Batch fetch products → brand map
            Map<Long, String> brandMap = new HashMap<>();
            for (Product p : productRepository.findAllById(pids)) {
                if (p.getBrand() != null && !p.getBrand().isBlank()) {
                    brandMap.put(p.getProductId(), p.getBrand());
                }
            }

            // Weighted brand affinity by event INTENT strength (mua > thêm giỏ > click > xem).
            // Mỗi event không còn đếm 1 đều nhau — brand được MUA nặng hơn brand chỉ lướt xem.
            Map<String, Double> brandScore = new HashMap<>();
            for (InteractionLog e : events) {
                try {
                    Long pid = Long.parseLong(e.getItemId());
                    String brand = brandMap.get(pid);
                    if (brand != null) {
                        brandScore.merge(brand, eventWeight(e.getEventType()), Double::sum);
                    }
                } catch (Exception ignored) {}
            }

            // Sort desc by weighted score, take top limit
            List<String> topBrands = brandScore.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            System.out.println("👤 getUserTopBrands(" + userId + "): " + topBrands
                    + " (from " + events.size() + " events)");

            return topBrands;
        } catch (Exception e) {
            System.err.println("getUserTopBrands fail for " + userId + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Trọng số event theo intent strength: mua > thêm giỏ > click chat > xem. Heuristic. */
    private static double eventWeight(String eventType) {
        if (eventType == null) return 1.0;
        switch (eventType) {
            case "CREATE_ORDER":       return 5.0;
            case "ADD_TO_CART":        return 3.0;
            case "CHAT_RESULT_CLICK":  return 2.0;
            case "VIEW_PRODUCT":       return 1.0;
            default:                   return 1.0;
        }
    }
}