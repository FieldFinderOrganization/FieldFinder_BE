package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.NotificationDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Notification;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.NotificationRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BookingRepository bookingRepository;
    private final PitchRepository pitchRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        if (booking == null) return;
        String pitchName = pitchNameOf(booking);
        // Báo khách đặt sân
        if (booking.getUser() != null) {
            notify(booking.getUser().getUserId(), "BOOKING_CONFIRMED",
                    "Đặt sân thành công",
                    "Lịch đặt " + pitchName + " ngày " + booking.getBookingDate() + " đã được xác nhận.",
                    "BOOKING", booking.getBookingId().toString());
        }
        // Báo (các) chủ sân có lịch đặt mới — query userId riêng để tránh lazy ở đường webhook
        for (UUID providerUserId : bookingRepository.findProviderUserIdsByBookingId(booking.getBookingId())) {
            if (providerUserId == null) continue;
            notify(providerUserId, "PROVIDER_BOOKING_NEW",
                    "Lịch đặt sân mới",
                    "Bạn có lịch đặt sân \"" + pitchName + "\" ngày " + booking.getBookingDate() + ".",
                    "BOOKING", booking.getBookingId().toString());
        }
    }

    @Override
    public void notifyProviderBookingCanceled(Booking booking) {
        if (booking == null) return;
        String pitchName = pitchNameOf(booking);
        for (UUID providerUserId : bookingRepository.findProviderUserIdsByBookingId(booking.getBookingId())) {
            if (providerUserId == null) continue;
            notify(providerUserId, "PROVIDER_BOOKING_CANCELED",
                    "Khách hủy lịch đặt",
                    "Lịch đặt sân \"" + pitchName + "\" ngày " + booking.getBookingDate() + " đã bị khách hủy.",
                    "BOOKING", booking.getBookingId().toString());
        }
    }

    @Override
    public void notifyProviderReviewApproved(UUID pitchId, String pitchName, Integer rating) {
        if (pitchId == null) return;
        UUID providerUserId = pitchRepository.findProviderUserIdByPitchId(pitchId);
        if (providerUserId == null) return;
        String name = (pitchName != null && !pitchName.isBlank()) ? pitchName : "sân của bạn";
        String stars = rating != null ? (" " + rating + "★") : "";
        notify(providerUserId, "PROVIDER_REVIEW_NEW",
                "Đánh giá mới",
                "Sân \"" + name + "\" vừa có đánh giá mới" + stars + ".",
                "PITCH", pitchId.toString());
    }

    /** Tên sân đầu tiên trong booking (an toàn lazy — caller phải trong session). */
    private String pitchNameOf(Booking booking) {
        if (booking.getBookingDetails() == null) return "sân";
        return booking.getBookingDetails().stream()
                .map(bd -> bd.getPitch() != null ? bd.getPitch().getName() : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("sân");
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
