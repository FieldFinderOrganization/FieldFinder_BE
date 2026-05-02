package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ChatClickRequestDTO;
import com.example.FieldFinder.dto.req.ChatFeedbackRequestDTO;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AIChatController.class)
@AutoConfigureMockMvc
class AIChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @InjectMocks
    private AIChat aiChatService;

    @InjectMocks
    private LogPublisherService logPublisherService;

    @InjectMocks
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser
    void testTrackChatClick() throws Exception {
        ChatClickRequestDTO request = new ChatClickRequestDTO();
        request.setSessionId("sess-123");
        request.setChatLogId("log-456");
        request.setClickedItemId("item-789");
        request.setItemType("PITCH");
        request.setPositionClicked(0);

        UUID mockUserId = UUID.randomUUID();
        when(userService.getUserIdBySession("sess-123")).thenReturn(mockUserId);

        mockMvc.perform(post("/api/ai/chat/click")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", "Test-UA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged"));

        verify(logPublisherService).publishEvent(
                eq(mockUserId.toString()), eq("sess-123"), eq("CHAT_RESULT_CLICK"),
                eq("item-789"), eq("PITCH"), anyMap(), eq("Test-UA")
        );
    }

    @Test
    @WithMockUser
    void testTrackChatFeedback() throws Exception {
        ChatFeedbackRequestDTO request = new ChatFeedbackRequestDTO();
        request.setSessionId("sess-123");
        request.setChatLogId("log-456");
        request.setFeedback("LIKE");
        request.setFeedbackText("Very helpful!");

        UUID mockUserId = UUID.randomUUID();
        when(userService.getUserIdBySession("sess-123")).thenReturn(mockUserId);

        mockMvc.perform(post("/api/ai/chat/feedback")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", "Test-UA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged"));

        verify(logPublisherService).publishEvent(
                eq(mockUserId.toString()), eq("sess-123"), eq("CHAT_FEEDBACK"),
                isNull(), isNull(), anyMap(), eq("Test-UA")
        );
    }
}