package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Booking;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
public class BookingResponseDTO {

    private String bookingId;
    private LocalDate bookingDate;
    private String status;
    private String paymentStatus;
    private BigDecimal totalPrice;
    private List<BookingDetailResponseDTO> bookingDetails;

    public static BookingResponseDTO fromEntity(Booking booking) {
        BookingResponseDTO dto = new BookingResponseDTO();
        dto.setBookingId(booking.getBookingId().toString());
        dto.setBookingDate(booking.getBookingDate());
        dto.setStatus(booking.getStatus().name()); // convert enum to String
        dto.setPaymentStatus(booking.getPaymentStatus().name()); // convert enum to String
        dto.setTotalPrice(booking.getTotalPrice());

        List<BookingDetailResponseDTO> details = booking.getBookingDetails().stream()
                .map(BookingDetailResponseDTO::fromEntity)
                .collect(Collectors.toList());
        dto.setBookingDetails(details);

        return dto;
    }
}
