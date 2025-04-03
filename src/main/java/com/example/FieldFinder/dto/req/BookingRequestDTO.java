package com.example.FieldFinder.dto.req;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
public class BookingRequestDTO {
    // Getters and Setters
    private UUID pitchId;
    private UUID userId;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private List<BookingDetailDTO> bookingDetails;

    @Setter
    @Getter
    public static class BookingDetailDTO {
        // Getters and Setters
        private LocalDateTime timeSlot;
        private String name;
        private BigDecimal priceDetail;

    }
}