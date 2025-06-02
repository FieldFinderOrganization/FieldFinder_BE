package com.example.FieldFinder.dto.req;
import com.example.FieldFinder.entity.Booking;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PaymentRequestDTO {
    private UUID bookingId;
    private UUID userId;
    private BigDecimal amount;
    private String paymentMethod;
}