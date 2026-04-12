package com.example.FieldFinder.dto.res;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponseDTO {
    private String transactionId;
    private String checkoutUrl;
    private String qrCode;
    private String amount;
    private String status;
    private String ownerName;
    private String ownerCardNumber;
    private String ownerBank;
}
