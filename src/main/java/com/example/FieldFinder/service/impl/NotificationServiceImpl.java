package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.NotificationDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Notification;
import com.example.FieldFinder.repository.NotificationRepository;
import com.example.FieldFinder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public Notification notify(UUID userId, String type, String title, String body,
                               String refType, String refId) {
        Notification saved = notificationRepository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(body)
                .refType(refType)
                .refId(refId)
                .build());
        // WS lỗi không được phá transaction nghiệp vụ đang gọi notify
        try {
            messagingTemplate.convertAndSend("/topic/notifications." + userId, NotificationDTO.from(saved));
        } catch (Exception e) {
            System.err.println("Không đẩy được notification realtime: " + e.getMessage());
        }
        return saved;
    }

    @Override
    public void notifyBookingConfirmed(Booking booking) {
        if (booking == null || booking.getUser() == null) return;
        String pitchName = booking.getBookingDetails().stream()
                .map(bd -> bd.getPitch() != null ? bd.getPitch().getName() : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("sân");
        notify(booking.getUser().getUserId(), "BOOKING_CONFIRMED",
                "Đặt sân thành công",
                "Lịch đặt " + pitchName + " ngày " + booking.getBookingDate() + " đã được xác nhận.",
                "BOOKING", booking.getBookingId().toString());
    }

    @Override
    public void pushTransient(UUID userId, Object payload) {
        try {
            messagingTemplate.convertAndSend("/topic/notifications." + userId, payload);
        } catch (Exception e) {
            System.err.println("Không đẩy được transient notification: " + e.getMessage());
        }
    }
}
