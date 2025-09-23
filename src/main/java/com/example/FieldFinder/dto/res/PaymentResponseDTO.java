package com.example.FieldFinder.dto.res;
import lombok.*;

import java.math.BigDecimal;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponseDTO {
    private String transactionId;
    private String checkoutUrl;
    private String amount;
    private String status;
}

