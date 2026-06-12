package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.ConversationDTO;
import com.example.FieldFinder.entity.ChatMessage;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ChatMessageRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.CloudinaryService;
import com.example.FieldFinder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;
    private final NotificationService notificationService;

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
            // Echo về sender để client thay optimistic message bằng bản có UUID thật
            // (cần cho reaction match theo messageId)
            if (!chatMessage.getSenderId().equals(chatMessage.getReceiverId())) {
                messagingTemplate.convertAndSend(
                        "/topic/messages." + chatMessage.getSenderId().toString(), savedMsg);

                // Event tạm cho banner thông báo toàn cục — không lưu DB
                // (unread chat đã có /api/chat/unread-count riêng)
                String senderName = userRepository.findById(savedMsg.getSenderId())
                        .map(User::getName)
                        .orElse("Người dùng");
                String preview = "IMAGE".equals(savedMsg.getType())
                        ? "Đã gửi một hình ảnh"
                        : savedMsg.getContent();
                notificationService.pushTransient(savedMsg.getReceiverId(), Map.of(
                        "type", "CHAT_MESSAGE",
                        "title", senderName,
                        "body", preview != null ? preview : "",
                        "refType", "CHAT",
                        "refId", savedMsg.getSenderId().toString()
                ));
            }
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

    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, String>> uploadChatImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("senderId") String senderId) {
        try {
            Map<String, Object> result = cloudinaryService.uploadChatImage(file, senderId);
            return ResponseEntity.ok(Map.of("imageUrl", result.get("url").toString()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload ảnh thất bại"));
        }
    }

    @PostMapping("/upload-video")
    public ResponseEntity<Map<String, String>> uploadChatVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("senderId") String senderId) {
        try {
            Map<String, Object> result = cloudinaryService.uploadChatVideo(file, senderId);
            return ResponseEntity.ok(Map.of("videoUrl", result.get("url").toString()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload video thất bại"));
        }
    }

    @PostMapping("/{messageId}/reaction")
    public ResponseEntity<Map<String, String>> reactToMessage(
            @PathVariable UUID messageId,
            @RequestParam UUID reactorId,
            @RequestParam(required = false) String emoji) {
        Optional<ChatMessage> msgOpt = chatMessageRepository.findById(messageId);
        if (msgOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ChatMessage msg = msgOpt.get();
        // Chỉ người nhận tin nhắn mới được thả reaction
        if (!msg.getReceiverId().equals(reactorId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chỉ người nhận mới được thả cảm xúc"));
        }
        String normalized = (emoji == null || emoji.isBlank()) ? null : emoji;
        msg.setReaction(normalized);
        chatMessageRepository.save(msg);

        // Báo realtime cho chủ tin nhắn (người thả tự update optimistic)
        Map<String, Object> event = new HashMap<>();
        event.put("type", "REACTION");
        event.put("messageId", messageId.toString());
        event.put("reaction", normalized);
        event.put("senderId", reactorId.toString());
        event.put("receiverId", msg.getSenderId().toString());
        messagingTemplate.convertAndSend("/topic/messages." + msg.getSenderId(), event);

        return ResponseEntity.ok(Map.of("message", "OK"));
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
                    ChatMessage last = lastMsgPage.isEmpty() ? null : lastMsgPage.getContent().getFirst();
                    long unread = chatMessageRepository.countUnreadFromSender(partnerId, userId);
                    return new ConversationDTO(
                            partnerId.toString(),
                            partner.getName(),
                            partner.getImageUrl(),
                            last != null ? previewOf(last) : "",
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

    private static String previewOf(ChatMessage msg) {
        if ("IMAGE".equals(msg.getType())) return "[Hình ảnh]";
        if ("VIDEO".equals(msg.getType())) return "[Video]";
        return msg.getContent();
    }
}