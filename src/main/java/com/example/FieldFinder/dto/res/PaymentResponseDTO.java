package com.example.FieldFinder.dto.res;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentResponseDTO {
    private String qrCodeUrl;
    private String bankAccountNumber;
    private String bankAccountName;
    private String bankName;
    private String amount;
    private String transactionId;
}
