package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.ConversationDTO;
import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ChatMessageRepository;
import com.example.FieldFinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin("*")
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

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

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDTO>> getConversations(@RequestParam UUID userId) {
        Set<UUID> partnerSet = new HashSet<>();
        partnerSet.addAll(chatMessageRepository.findDistinctReceivers(userId));
        partnerSet.addAll(chatMessageRepository.findDistinctSenders(userId));
        partnerSet.remove(userId);
        List<UUID> partners = new ArrayList<>(partnerSet);
        List<ConversationDTO> result = partners.stream()
                .map(partnerId -> {
                    Optional<User> partnerOpt = userRepository.findById(partnerId);
                    if (partnerOpt.isEmpty()) return null;
                    User partner = partnerOpt.get();
                    Page<ChatMessage> lastMsgPage = chatMessageRepository
                            .getConversation(userId, partnerId, PageRequest.of(0, 1));
                    ChatMessage last = lastMsgPage.isEmpty() ? null : lastMsgPage.getContent().get(0);
                    long unread = chatMessageRepository.countUnreadFromSender(partnerId, userId);
                    return new ConversationDTO(
                            partnerId.toString(),
                            partner.getName(),
                            partner.getImageUrl(),
                            last != null ? last.getContent() : "",
                            last != null ? last.getTimestamp() : null,
                            last != null && last.getSenderId().equals(userId),
                            unread
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ConversationDTO::lastMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}