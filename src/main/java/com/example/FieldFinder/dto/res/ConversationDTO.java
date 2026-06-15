package com.example.FieldFinder.dto.res;

import java.util.Date;

public record ConversationDTO(
        String otherUserId,
        String otherUserName,
        String otherUserImageUrl,
        String otherUserRole,
        String lastMessage,
        Date lastMessageTime,
        boolean isLastMessageFromMe,
        long unreadCount
) {}