package com.example.FieldFinder.dto;


import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentDto {
    private UUID paymentId;
    private UUID bookingId;
    private Double amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paymentDate;
}

