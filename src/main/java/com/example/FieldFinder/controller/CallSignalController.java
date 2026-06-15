package com.example.FieldFinder.controller;

import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ChatMessageRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Cuộc gọi thoại user↔provider (Phase 1).
 *
 * <p>Signaling WebRTC (INVITE/ANSWER/ICE/REJECT/HANGUP/CANCEL/BUSY) đi qua STOMP dạng
 * relay ephemeral — KHÔNG lưu DB, đúng pattern TYPING trong {@link ChatController}.
 * Chỉ kết quả cuộc gọi mới được lưu thành {@link ChatMessage} type=CALL (kiểu Messenger).</p>
 */
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallSignalController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // coturn REST auth (use-auth-secret) — secret phải khớp static-auth-secret của coturn.
    @Value("${turn.secret:fieldfinder-turn-secret}")
    private String turnSecret;
    // Có thể khai báo nhiều URL, ngăn cách bằng dấu phẩy.
    @Value("${turn.urls:turn:localhost:3478}")
    private String turnUrls;
    @Value("${turn.stun-urls:stun:stun.l.google.com:19302}")
    private String stunUrls;
    // TTL credential TURN (giây) — mặc định 12h.
    @Value("${turn.ttl-seconds:43200}")
    private long turnTtlSeconds;

    // ---- Signaling: relay nguyên trạng tới người nhận, không chạm DB ----
    @MessageMapping("/call.signal")
    public void relaySignal(@Payload Map<String, Object> signal) {
        Object to = signal.get("toId");
        if (to == null) return;
        messagingTemplate.convertAndSend("/topic/call." + to.toString(), signal);
    }

    // ---- ICE config: STUN công khai + TURN coturn với credential ngắn hạn ----
    @GetMapping("/ice-config")
    public ResponseEntity<Map<String, Object>> iceConfig(
            @RequestParam(required = false) String userId) {
        long expiry = System.currentTimeMillis() / 1000L + turnTtlSeconds;
        String username = expiry + ":" + (userId == null || userId.isBlank() ? "anon" : userId);
        String credential = hmacSha1Base64(turnSecret, username);

        List<Map<String, Object>> iceServers = new ArrayList<>();
        for (String s : stunUrls.split(",")) {
            if (!s.isBlank()) iceServers.add(Map.of("urls", s.trim()));
        }
        List<String> turns = new ArrayList<>();
        for (String t : turnUrls.split(",")) {
            if (!t.isBlank()) turns.add(t.trim());
        }
        if (!turns.isEmpty()) {
            iceServers.add(Map.of(
                    "urls", turns,
                    "username", username,
                    "credential", credential
            ));
        }
        return ResponseEntity.ok(Map.of("iceServers", iceServers));
    }

    // ---- Lưu kết quả cuộc gọi → ChatMessage type=CALL (chỉ caller gọi, tránh ghi trùng) ----
    @PostMapping("/log")
    public ResponseEntity<ChatMessage> logCall(@RequestBody CallLogRequest req) {
        if (req.senderId() == null || req.receiverId() == null) {
            return ResponseEntity.badRequest().build();
        }
        ChatMessage msg = ChatMessage.builder()
                .senderId(req.senderId())
                .receiverId(req.receiverId())
                .type("CALL")
                .content("")
                .callStatus(req.status() == null ? "ANSWERED" : req.status())
                .callDurationSec(req.durationSec() == null ? 0 : req.durationSec())
                .callMedia(req.media() == null ? "AUDIO" : req.media())
                .timestamp(new Date())
                .isRead(false)
                .build();
        ChatMessage saved = chatMessageRepository.save(msg);

        // Đẩy vào luồng chat của cả hai (giống ChatController.processMessage)
        messagingTemplate.convertAndSend("/topic/messages." + saved.getReceiverId(), saved);
        if (!saved.getSenderId().equals(saved.getReceiverId())) {
            messagingTemplate.convertAndSend("/topic/messages." + saved.getSenderId(), saved);
        }

        // Cuộc gọi nhỡ → LƯU vào chuông + badge (persist), không còn transient
        if ("MISSED".equals(saved.getCallStatus())) {
            String callerName = userRepository.findById(saved.getSenderId())
                    .map(User::getName)
                    .orElse("Người dùng");
            notificationService.notify(saved.getReceiverId(), "CALL_MISSED",
                    callerName,
                    "Cuộc gọi thoại nhỡ",
                    "CALL", saved.getSenderId().toString());
        }
        return ResponseEntity.ok(saved);
    }

    private static String hmacSha1Base64(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Không tạo được TURN credential", e);
        }
    }

    public record CallLogRequest(UUID senderId, UUID receiverId, String status,
                                 Integer durationSec, String media) {
    }
}
