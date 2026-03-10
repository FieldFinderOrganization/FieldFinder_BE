package com.example.FieldFinder.controller;

import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;

    @MessageMapping("/chat")
    public void processMessage(@Payload ChatMessage chatMessage) {

        chatMessage.setTimestamp(new Date());

        try {
            ChatMessage savedMsg = chatMessageRepository.save(chatMessage);
            System.out.println("Đã lưu tin nhắn vào Database thành công!");

            String destination = "/topic/messages/" + chatMessage.getReceiverId().toString();
            messagingTemplate.convertAndSend(destination, savedMsg);

            System.out.println("Đã gửi (forward) tin nhắn tới kênh: " + destination);

        } catch (Exception e) {
            System.err.println("Không thể xử lý tin nhắn!");
            e.printStackTrace();
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatMessage>> getChatHistory(
            @RequestParam UUID user1,
            @RequestParam UUID user2) {

        List<ChatMessage> history = chatMessageRepository.getConversation(user1, user2);
        return ResponseEntity.ok(history);
    }
}