package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Notification;

import java.util.UUID;

public interface NotificationService {

    /** Lưu notification vào DB rồi đẩy realtime tới /topic/notifications.{userId}. */
    Notification notify(UUID userId, String type, String title, String body, String refType, String refId);

    /** Chỉ đẩy realtime, không lưu DB (dùng cho event tạm như tin nhắn chat). */
    void pushTransient(UUID userId, Object payload);

    /** Báo booking đã xác nhận — dùng chung cho CASH, payment polling và webhook. */
    void notifyBookingConfirmed(Booking booking);
}
