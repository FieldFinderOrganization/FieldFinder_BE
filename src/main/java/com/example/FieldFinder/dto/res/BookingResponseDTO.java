package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Pitch;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Setter
public class BookingResponseDTO {

    private UUID bookingId;
    private LocalDate bookingDate;
    private String status;
    private String paymentStatus;
    private BigDecimal totalPrice;
    private UUID providerId;
    private List<BookingDetailResponseDTO> bookingDetails;

    public static BookingResponseDTO fromEntity(Booking booking) {
        BookingResponseDTO dto = new BookingResponseDTO();
        dto.setBookingId(booking.getBookingId());
        dto.setBookingDate(booking.getBookingDate());
        dto.setStatus(booking.getStatus().name());
        dto.setPaymentStatus(booking.getPaymentStatus().name());
        dto.setTotalPrice(booking.getTotalPrice());

        List<BookingDetailResponseDTO> details = booking.getBookingDetails().stream()
                .map(BookingDetailResponseDTO::fromEntity)
                .collect(Collectors.toList());
        dto.setBookingDetails(details);

        // Lấy providerId từ bookingDetail đầu tiên (nếu có)
        if (!booking.getBookingDetails().isEmpty()) {
            BookingDetail firstDetail = booking.getBookingDetails().get(0);
            Pitch pitch = firstDetail.getPitch();
            if (pitch != null &&
                    pitch.getProviderAddress() != null &&
                    pitch.getProviderAddress().getProvider() != null) {

                dto.setProviderId(pitch.getProviderAddress().getProvider().getProviderId());
            }
        }

        return dto;
    }
}
