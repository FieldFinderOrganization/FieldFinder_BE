package com.example.FieldFinder.service;

import com.example.FieldFinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.context.event.EventListener;

import java.security.Principal;
import java.util.Date;
import java.util.UUID;

/**
 * Presence "mức 2": ghi mốc hoạt động cuối (lastSeenAt) khi user CONNECT/DISCONNECT WS.
 * Không heartbeat, không presence-store realtime. Disconnect (kể cả rớt mạng) được Spring
 * bắn SessionDisconnectEvent → cập nhật mốc rời thật.
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UserRepository userRepository;

    /** Ghi lastSeenAt = now. Async để không chặn luồng WS; lỗi nuốt (presence không critical). */
    @Async
    @Transactional
    public void markSeen(UUID userId) {
        if (userId == null) return;
        try {
            userRepository.updateLastSeen(userId, new Date());
        } catch (Exception ignored) {
        }
    }

    /** WS đóng (graceful hoặc rớt) → ghi mốc rời. userId lấy từ Principal đã gắn ở interceptor CONNECT. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (user == null) return;
        try {
            markSeen(UUID.fromString(user.getName()));
        } catch (IllegalArgumentException ignored) {
        }
    }
}
