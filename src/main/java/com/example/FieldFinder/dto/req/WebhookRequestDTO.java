package com.example.FieldFinder.dto.req;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookRequestDTO {

    @JsonProperty("data")
    private PaymentData data;

    @Data
    public static class PaymentData {
        @JsonProperty("paymentLinkId")
        private String transactionId;
    }
}