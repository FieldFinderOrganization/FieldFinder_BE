package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.PasskeyLoginFinishRequestDTO;
import com.example.FieldFinder.dto.req.PasskeyRegisterFinishRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyLoginStartResponseDTO;
import com.example.FieldFinder.dto.res.PasskeyRegisterStartResponseDTO;
import com.example.FieldFinder.entity.PasskeyCredential;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.PasskeyCredentialRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.PasskeyService;
import com.example.FieldFinder.service.RedisService;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasskeyServiceImpl implements PasskeyService {

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final JwtService jwtService;

    // webauthn4j — dùng NonStrict để không bắt buộc attestation statement hợp lệ
    // (phù hợp cho development và khi không cần verify thiết bị cụ thể)
    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${passkey.rp-id}")
    private String rpId;

    @Value("${passkey.rp-name}")
    private String rpName;

    @Value("${passkey.rp-origin}")
    private String rpOrigin;

    // -------------------------------------------------------
    // REGISTRATION
    // -------------------------------------------------------

    @Override
    public PasskeyRegisterStartResponseDTO startRegistration(String userEmail) {
        User user = findUserByEmail(userEmail);

        // Tạo 32 byte random challenge
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        String challengeBase64 = encodeBase64Url(challengeBytes);

        // Lưu challenge vào Redis — subject = userId
        String challengeId = UUID.randomUUID().toString();
        redisService.savePasskeyChallenge(challengeId, user.getUserId().toString(), challengeBase64);

        // userId dạng base64url để truyền cho WebAuthn user.id
        String userIdBase64 = encodeBase64Url(uuidToBytes(user.getUserId()));

        return new PasskeyRegisterStartResponseDTO(
                challengeId,
                challengeBase64,
                rpId,
                rpName,
                userIdBase64,
                user.getEmail(),
                user.getName()
        );
    }

    @Override
    @Transactional
    public void finishRegistration(String userEmail, PasskeyRegisterFinishRequestDTO dto) {
        log.info("Finish Registration started with DTO: {}", dto);

        // Lấy và XÓA challenge khỏi Redis (one-time use)
        String[] challengeData = redisService.consumePasskeyChallenge(dto.getChallengeId());
        String userId  = challengeData[0];
        String challengeBase64 = challengeData[1];

        // Verify challenge thuộc đúng user đang đăng nhập
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User không tồn tại"));

        if (!user.getEmail().equals(userEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Challenge không thuộc tài khoản hiện tại.");
        }

        // Decode dữ liệu từ client
        log.debug("PassKey Register Start - clientDataJSON: {}, attestationObject: {}",
                dto.getClientDataJSON(), dto.getAttestationObject());

        byte[] clientDataJSONBytes   = decodeBase64Url(dto.getClientDataJSON());
        byte[] attestationObjectBytes = decodeBase64Url(dto.getAttestationObject());
        byte[] challengeBytes        = decodeBase64Url(challengeBase64);

        log.debug("Decoded clientDataJSON (first 10 chars): {}",
                new String(clientDataJSONBytes, 0, Math.min(clientDataJSONBytes.length, 10)));

        // Build WebAuthn server property
        ServerProperty serverProperty = new ServerProperty(
                new Origin(rpOrigin),
                rpId,
                new DefaultChallenge(challengeBytes),
                null  // tokenBindingId — không dùng
        );

        // Parse + validate attestation
        RegistrationRequest registrationRequest = new RegistrationRequest(
                attestationObjectBytes, clientDataJSONBytes);
        RegistrationParameters registrationParameters = new RegistrationParameters(
                serverProperty,
                null,
                false,  // userVerificationRequired
                false   // userPresenceRequired
        );

        RegistrationData registrationData;
        try {
            registrationData = webAuthnManager.parse(registrationRequest);
            webAuthnManager.validate(registrationData, registrationParameters);
        } catch (Exception e) {
            log.error("PassKey registration validation failed", e);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Xác thực PassKey thất bại: " + e.getMessage());
        }

        // Trích xuất credential data từ authData
        assert registrationData
                .getAttestationObject() != null;
        AttestedCredentialData credData = registrationData
                .getAttestationObject()
                .getAuthenticatorData()
                .getAttestedCredentialData();

        assert credData != null;
        byte[] credentialIdBytes = credData.getCredentialId();
        String credentialIdBase64 = encodeBase64Url(credentialIdBytes);
        long signCount = registrationData.getAttestationObject()
                .getAuthenticatorData().getSignCount();

        // Serialize COSE public key để lưu vào DB
        byte[] coseKeyBytes = objectConverter.getCborConverter()
                .writeValueAsBytes(credData.getCOSEKey());

        if (passkeyCredentialRepository.existsByCredentialId(credentialIdBase64)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PassKey này đã được đăng ký. Vui lòng dùng thiết bị khác.");
        }

        // Lưu vào DB
        PasskeyCredential credential = PasskeyCredential.builder()
                .user(user)
                .credentialId(credentialIdBase64)
                .publicKeyCose(coseKeyBytes)
                .signCount(signCount)
                .displayName(dto.getDisplayName())
                .createdAt(LocalDateTime.now())
                .build();

        passkeyCredentialRepository.save(credential);
        log.info("✅ PassKey registered for user={}, device={}", userEmail, dto.getDisplayName());
    }

    // -------------------------------------------------------
    // AUTHENTICATION
    // -------------------------------------------------------

    @Override
    public PasskeyLoginStartResponseDTO startLogin(String email) {
        User user = findUserByEmail(email);

        List<PasskeyCredential> credentials = passkeyCredentialRepository.findByUser(user);
        if (credentials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Tài khoản này chưa đăng ký PassKey. " +
                            "Vui lòng đăng nhập bằng phương thức khác rồi thêm PassKey trong cài đặt.");
        }

        // Tạo challenge
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        String challengeBase64 = encodeBase64Url(challengeBytes);

        String challengeId = UUID.randomUUID().toString();
        // subject = email (để dùng khi finishLogin)
        redisService.savePasskeyChallenge(challengeId, email, challengeBase64);

        List<String> allowCredentials = credentials.stream()
                .map(PasskeyCredential::getCredentialId)
                .collect(Collectors.toList());

        return new PasskeyLoginStartResponseDTO(challengeId, challengeBase64, rpId, allowCredentials);
    }

    @Override
    @Transactional
    public AuthTokenResponseDTO finishLogin(PasskeyLoginFinishRequestDTO dto) {
        log.info("Finish Login started with DTO: {}", dto);

        // Lấy và XÓA challenge (one-time use)
        String[] challengeData = redisService.consumePasskeyChallenge(dto.getChallengeId());
        String email           = challengeData[0];
        String challengeBase64 = challengeData[1];

        // Tìm credential theo credentialId
        PasskeyCredential stored = passkeyCredentialRepository
                .findByCredentialId(dto.getCredentialId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "PassKey không hợp lệ hoặc chưa được đăng ký trên thiết bị này."));

        // Verify credential thuộc đúng user
        if (!stored.getUser().getEmail().equals(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "PassKey không thuộc tài khoản này.");
        }

        // Decode dữ liệu từ client
        log.debug("PassKey Login start - clientDataJSON: {}, authData: {}, signature: {}",
                dto.getClientDataJSON(), dto.getAuthenticatorData(), dto.getSignature());

        byte[] credentialIdBytes     = decodeBase64Url(dto.getCredentialId());
        byte[] clientDataJSONBytes   = decodeBase64Url(dto.getClientDataJSON());
        byte[] authenticatorDataBytes = decodeBase64Url(dto.getAuthenticatorData());
        byte[] signatureBytes        = decodeBase64Url(dto.getSignature());
        byte[] challengeBytes        = decodeBase64Url(challengeBase64);

        log.debug("Decoded clientDataJSON (first 10 chars): {}",
                new String(clientDataJSONBytes, 0, Math.min(clientDataJSONBytes.length, 10)));

        com.webauthn4j.data.attestation.authenticator.COSEKey storedCOSEKey;
        try {
            storedCOSEKey = objectConverter.getCborConverter()
                    .readValue(stored.getPublicKeyCose(),
                            com.webauthn4j.data.attestation.authenticator.COSEKey.class);
        } catch (Exception e) {
            log.error("Không thể deserialize COSE key cho credentialId={}", dto.getCredentialId(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi đọc PassKey. Vui lòng đăng ký lại thiết bị.");
        }

        // Build authenticator từ stored credential
        assert storedCOSEKey != null;
        AttestedCredentialData attestedCredentialData = new AttestedCredentialData(
                AAGUID.ZERO, credentialIdBytes, storedCOSEKey);
        AuthenticatorImpl authenticator = new AuthenticatorImpl(
                attestedCredentialData, null, stored.getSignCount());

        // Build server property
        ServerProperty serverProperty = new ServerProperty(
                new Origin(rpOrigin), rpId,
                new DefaultChallenge(challengeBytes), null);

        // Parse + validate assertion
        byte[] userHandleBytes = decodeBase64Url(dto.getUserHandle());

        AuthenticationRequest authRequest = new AuthenticationRequest(
                credentialIdBytes,
                userHandleBytes,
                authenticatorDataBytes,
                clientDataJSONBytes,
                signatureBytes
        );
        AuthenticationParameters authParams = new AuthenticationParameters(
                serverProperty, authenticator, null, false);

        AuthenticationData authData;
        try {
            authData = webAuthnManager.parse(authRequest);
            webAuthnManager.validate(authData, authParams);
        } catch (Exception e) {
            log.error("PassKey authentication validation failed for user={}", email, e);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Xác thực PassKey thất bại: " + e.getMessage());
        }

        // Cập nhật signCount (chống replay attack)
        // Nếu signCount giảm → log cảnh báo thay vì block ngay
        // (iCloud Keychain / Google Password Manager có thể sync lệch signCount)
        assert authData.getAuthenticatorData() != null;
        long newSignCount = authData.getAuthenticatorData().getSignCount();
        if (newSignCount != 0 && newSignCount <= stored.getSignCount()) {
            log.warn("PassKey signCount anomaly — có thể thiết bị bị clone. " +
                            "credentialId={}, stored={}, received={}. Proceeding.",
                    dto.getCredentialId(), stored.getSignCount(), newSignCount);
        }
        stored.setSignCount(Math.max(newSignCount, stored.getSignCount()));
        passkeyCredentialRepository.save(stored);

        log.info("PassKey login success for user={}", email);
        return jwtService.generateTokenPair(stored.getUser());
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy tài khoản với email: " + email));
    }

    /** Encode bytes → base64url (không padding) */
    private String encodeBase64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Decode base64url (chấp nhận cả standard base64 và base64url, có/không padding).
     * Mobile SDK có thể gửi standard base64 thay vì base64url.
     */
    private byte[] decodeBase64Url(String input) {
        if (input == null || input.isBlank()) {
            return new byte[0];
        }
        // Normalize: trim whitespace, chuẩn hóa về base64url không padding
        String normalized = input.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace('+', '-')
                .replace('/', '_')
                .replaceAll("=+$", "");
        return Base64.getUrlDecoder().decode(normalized);
    }

    private byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}