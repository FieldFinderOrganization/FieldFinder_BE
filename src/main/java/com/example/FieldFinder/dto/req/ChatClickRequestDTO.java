package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class ChatClickRequestDTO {
    private String sessionId;
    private String chatLogId;
    private String clickedItemId;
    private String itemType;     // PRODUCT, PITCH
    private Integer positionClicked;
}