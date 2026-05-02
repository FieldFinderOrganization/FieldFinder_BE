package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class ChatFeedbackRequestDTO {
    private String sessionId;
    private String chatLogId;
    private String feedback;     // THUMBS_UP, THUMBS_DOWN
    private String feedbackText; // Optional detailed text
}