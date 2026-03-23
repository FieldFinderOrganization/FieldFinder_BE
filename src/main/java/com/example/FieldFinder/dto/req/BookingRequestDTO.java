package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.entity.TimeSlot;
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
    private UUID pitchId;
    private UUID userId;
    private LocalDate bookingDate;
    private BigDecimal totalPrice;
    private List<BookingDetailDTO> bookingDetails;

    @Setter
    @Getter
    public static class BookingDetailDTO {
        private TimeSlot timeSlot;
        private String name;
        private BigDecimal priceDetail;

    }
}