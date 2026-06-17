package com.example.FieldFinder.config;

import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.PresenceService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Gắn Principal (userId từ JWT) vào session WS lúc CONNECT để SessionDisconnectEvent biết
 * user nào rời → cập nhật lastSeenAt. Cũng ghi mốc "vừa online" ngay khi connect.
 * Không ảnh hưởng bảo mật: token lỗi chỉ bỏ qua presence, không chặn kết nối.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final PresenceService presenceService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            UUID userId = resolveUserId(accessor);
            if (userId != null) {
                final String uid = userId.toString();
                accessor.setUser((Principal) () -> uid); // dính vào session → disconnect biết user
                presenceService.markSeen(userId);          // vừa online
            }
        }
        return message;
    }

    private UUID resolveUserId(StompHeaderAccessor accessor) {
        try {
            List<String> auth = accessor.getNativeHeader("Authorization");
            if (auth == null || auth.isEmpty()) return null;
            String header = auth.get(0);
            if (header == null || !header.startsWith("Bearer ")) return null;
            Claims claims = jwtService.verifyAccessToken(header.substring(7).trim());
            String uid = claims.get("userId", String.class);
            return uid != null ? UUID.fromString(uid) : null;
        } catch (Exception e) {
            return null; // token lỗi/hết hạn → bỏ qua presence
        }
    }
}
