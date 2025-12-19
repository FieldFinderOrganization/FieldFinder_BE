package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChatShop;
import com.example.FieldFinder.dto.req.ShopQueryDTO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/shop")
public class AIChatShopController {

    private final AIChatShop aiChatShop;

    public AIChatShopController(AIChatShop aiChatShop) {
        this.aiChatShop = aiChatShop;
    }

    @PostMapping("/chat")
    public ShopQueryDTO chat(
            @RequestParam String message,
            @RequestParam String sessionId
    ) {
        return aiChatShop.handleShopChat(message, sessionId);
    }
}
