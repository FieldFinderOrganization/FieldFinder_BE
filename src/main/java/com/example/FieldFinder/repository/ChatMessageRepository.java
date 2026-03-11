package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ChatMessage;
import io.lettuce.core.dynamic.annotation.Param;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("SELECT c FROM ChatMessage c WHERE (c.senderId = :user1 AND c.receiverId = :user2) OR (c.senderId = : user2 AND c.receiverId = : user1) ORDER BY c.timestamp DESC")
    Page<ChatMessage> getConversation(@Param("user1") UUID user1, @Param("user2") UUID user2, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage c SET c.isRead = true WHERE c.senderId = :senderId AND c.receiverId = :receiverId AND c.isRead = false")
    void markMessagesAsRead(@Param("senderId") UUID senderId, @Param("receiverId") UUID receiverId);

    @Query("SELECT COUNT(c) FROM ChatMessage c WHERE c.receiverId = :receiverId AND c.isRead = false")
    long countUnreadMessages(@Param("receiverId") UUID receiverId);
}
