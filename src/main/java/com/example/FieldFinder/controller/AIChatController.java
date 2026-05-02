package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ChatClickRequestDTO;
import com.example.FieldFinder.dto.req.ChatFeedbackRequestDTO;
import com.example.FieldFinder.dto.req.ChatRequestDTO;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.example.FieldFinder.ai.AIChat.BookingQuery;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIChatController {

    private final AIChat aiChatService;
    private final LogPublisherService logPublisherService;
    private final UserService userService;

    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AIChat.BookingQuery> handleChat(
            @RequestBody ChatRequestDTO request) {

        aiChatService.pauseEnrichment();
        try {
            AIChat.BookingQuery response = aiChatService.parseBookingInput(
                    request.getUserInput(),
                    request.getSessionId()
            );
            return ResponseEntity.ok(response);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            AIChat.BookingQuery errorQuery = new AIChat.BookingQuery();
            errorQuery.message = "Xin lỗi, tôi đang gặp sự cố. Vui lòng thử lại sau.";
            return ResponseEntity.status(500).body(errorQuery);
        } catch (IllegalArgumentException e) {
            AIChat.BookingQuery errorQuery = new AIChat.BookingQuery();
            errorQuery.message = "Xin lỗi, tôi không hiểu yêu cầu của bạn. " + e.getMessage();
            return ResponseEntity.status(400).body(errorQuery);
        } finally {
            aiChatService.resumeEnrichment();
        }
    }

    @PostMapping("/image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BookingQuery> chatWithImage(@RequestBody Map<String, String> payload) {
        String base64Image = payload.get("image");

        String sessionId = payload.getOrDefault("sessionId", "guest_session");

        if (base64Image == null || base64Image.isEmpty()) {
            BookingQuery error = new BookingQuery();
            error.message = "Vui lòng gửi ảnh (Base64 string).";
            return ResponseEntity.badRequest().body(error);
        }

        aiChatService.pauseEnrichment();
        try {
            BookingQuery result = aiChatService.processImageSearchWithGemini(base64Image, sessionId);
            return ResponseEntity.ok(result);
        } finally {
            aiChatService.resumeEnrichment();
        }
    }

    @PostMapping("/chat/click")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> trackChatClick(
            @RequestBody ChatClickRequestDTO request, HttpServletRequest httpRequest) {
        try {
            String userId = resolveUserId(request.getSessionId());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chat_log_id", request.getChatLogId());
            metadata.put("clicked_item_id", request.getClickedItemId());
            metadata.put("item_type", request.getItemType());
            metadata.put("position_clicked", request.getPositionClicked());

            logPublisherService.publishEvent(
                    userId, request.getSessionId(),
                    "CHAT_RESULT_CLICK",
                    request.getClickedItemId(),
                    request.getItemType(),
                    metadata,
                    httpRequest.getHeader("User-Agent")
            );
            return ResponseEntity.ok(Map.of("status", "logged"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/chat/feedback")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> trackChatFeedback(
            @RequestBody ChatFeedbackRequestDTO request, HttpServletRequest httpRequest) {
        try {
            String userId = resolveUserId(request.getSessionId());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chat_log_id", request.getChatLogId());
            metadata.put("feedback", request.getFeedback());
            if (request.getFeedbackText() != null && !request.getFeedbackText().isBlank()) {
                metadata.put("feedback_text", request.getFeedbackText());
            }

            logPublisherService.publishEvent(
                    userId, request.getSessionId(),
                    "CHAT_FEEDBACK",
                    null, null,
                    metadata,
                    httpRequest.getHeader("User-Agent")
            );
            return ResponseEntity.ok(Map.of("status", "logged"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String resolveUserId(String sessionId) {
        if (sessionId == null) return null;
        UUID uid = userService.getUserIdBySession(sessionId);
        return uid != null ? uid.toString() : null;
    }
}