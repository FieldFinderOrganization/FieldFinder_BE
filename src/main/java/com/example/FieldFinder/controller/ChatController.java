package com.example.FieldFinder.controller;

import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin("*")
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;

    @MessageMapping("/chat")
    public void processMessage(@Payload ChatMessage chatMessage) {
        chatMessage.setTimestamp(new Date());

        if ("TYPING".equals(chatMessage.getType())) {
            String destination = "/topic/messages." + chatMessage.getReceiverId().toString();
            messagingTemplate.convertAndSend(destination, chatMessage);
            return;
        }

        chatMessage.setIsRead(false);
        try {
            ChatMessage savedMsg = chatMessageRepository.save(chatMessage);
            String destination = "/topic/messages." + chatMessage.getReceiverId().toString();
            messagingTemplate.convertAndSend(destination, savedMsg);
        } catch (Exception e) {
            System.err.println("❌ LỖI RỒI: Không thể xử lý tin nhắn!");
            e.printStackTrace();
        }
    }

    @GetMapping("/history")
    public ResponseEntity<Page<ChatMessage>> getChatHistory(
            @RequestParam UUID user1,
            @RequestParam UUID user2,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatMessage> history = chatMessageRepository.getConversation(user1, user2, pageable);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/mark-read")
    public ResponseEntity<Map<String, String>> markAsRead(@RequestParam UUID senderId, @RequestParam UUID receiverId) {
        chatMessageRepository.markMessagesAsRead(senderId, receiverId);
        return ResponseEntity.ok(Map.of("message", "Đã đánh dấu đọc thành công"));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(@RequestParam UUID userId) {
        return ResponseEntity.ok(chatMessageRepository.countUnreadMessages(userId));
    }
}