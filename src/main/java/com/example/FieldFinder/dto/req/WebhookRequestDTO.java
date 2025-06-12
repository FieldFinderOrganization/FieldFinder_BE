package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class WebhookRequestDTO {
    private String transactionId;
    private String status;
}
