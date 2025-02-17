package com.example.FieldFinder.dto;

package com.pitchbooking.application.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingDto {
    private UUID bookingId;
    private UUID userId;
    private UUID pitchId;
    private LocalDateTime bookingTime;
    private Double totalPrice;
    private String status;
}

