package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Notification;

import java.time.LocalDateTime;

public record NotificationDTO(
        String id,
        String type,
        String title,
        String body,
        String refType,
        String refId,
        boolean isRead,
        LocalDateTime createdAt
) {
    public static NotificationDTO from(Notification n) {
        return new NotificationDTO(
                n.getNotificationId().toString(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getRefType(),
                n.getRefId(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
