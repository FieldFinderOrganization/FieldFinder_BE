package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProviderBookingResponseDTO {
    private UUID userId;
    private String userName;

    private UUID bookingId;
    private LocalDate bookingDate;
    private String status;
    private String paymentStatus;
    private BigDecimal totalPrice;
    private UUID providerId;

    private String paymentMethod;
    private String providerName;
    private String pitchName;
    private List<Integer> slots;
}