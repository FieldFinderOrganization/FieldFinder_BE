package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ChatRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.FieldFinder.ai.AIChat.BookingQuery;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin("*")
public class AIChatController {

    private final AIChat aiChatService;

    @PostMapping("/chat")
    public ResponseEntity<AIChat.BookingQuery> handleChat(
            @RequestBody ChatRequestDTO request) {

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
            // Xử lý lỗi nếu AI trả về JSON rác
            AIChat.BookingQuery errorQuery = new AIChat.BookingQuery();
            errorQuery.message = "Xin lỗi, tôi không hiểu yêu cầu của bạn. " + e.getMessage();
            return ResponseEntity.status(400).body(errorQuery);
        }
    }

    @PostMapping("/image")
    public ResponseEntity<BookingQuery> chatWithImage(@RequestBody Map<String, String> payload) {
        // 1. Lấy chuỗi base64 từ frontend gửi lên
        String base64Image = payload.get("image");

        if (base64Image == null || base64Image.isEmpty()) {
            BookingQuery error = new BookingQuery();
            error.message = "Vui lòng gửi ảnh (Base64 string).";
            return ResponseEntity.badRequest().body(error);
        }

        // 2. Gọi hàm processImageSearchWithGemini (Hàm bạn vừa sửa)
        BookingQuery result = aiChatService.processImageSearchWithGemini(base64Image);

        // 3. Trả kết quả về cho Frontend
        return ResponseEntity.ok(result);
    }
}