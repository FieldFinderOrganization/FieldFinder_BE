package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ChatMessage;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("SELECT c FROM ChatMessage c WHERE (c.senderId = :user1 AND c.receiverId = :user2) OR (c.senderId = :user2 AND c.receiverId = :user1) ORDER BY c.timestamp ASC")
    List<ChatMessage> getConversation(@Param("user1") UUID user1, @Param("user2") UUID user2);
}
