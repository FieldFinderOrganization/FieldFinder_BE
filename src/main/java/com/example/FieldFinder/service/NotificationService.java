package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Notification;

import java.util.UUID;

public interface NotificationService {

    /** Lưu notification vào DB rồi đẩy realtime tới /topic/notifications.{userId}. */
    Notification notify(UUID userId, String type, String title, String body, String refType, String refId);

    /** Chỉ đẩy realtime, không lưu DB (dùng cho event tạm như tin nhắn chat). */
    void pushTransient(UUID userId, Object payload);

    /** Báo booking đã xác nhận — dùng chung cho CASH, payment polling và webhook.
     *  Đồng thời báo chủ sân có lịch đặt mới (PROVIDER_BOOKING_NEW). */
    void notifyBookingConfirmed(Booking booking);

    /** Báo chủ sân khi khách hủy lịch đặt (PROVIDER_BOOKING_CANCELED). */
    void notifyProviderBookingCanceled(Booking booking);

    /** Báo khách khi lịch đặt bị chủ sân / hệ thống hủy (BOOKING_CANCELED) — kèm lý do.
     *  Đọc cancelledBy + cancelReason đã set sẵn trên booking. */
    void notifyUserBookingCanceled(Booking booking);

    /** Báo chủ sân khi đánh giá sân được duyệt (PROVIDER_REVIEW_NEW). */
    void notifyProviderReviewApproved(UUID pitchId, String pitchName, Integer rating);
}
