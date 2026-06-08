package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class ChatRequestDTO {
    private String userInput;
    private String sessionId;
    // Optional live GPS from the app (nullable). Falls back to saved profile coords when absent.
    private Double latitude;
    private Double longitude;
}
