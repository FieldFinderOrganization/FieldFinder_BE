package com.example.FieldFinder.dto.req;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequestDTO {
    private UUID pitchId;
    private UUID userId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate bookingDate;

    private BigDecimal totalPrice;
    private List<BookingDetailDTO> bookingDetails;
    private String paymentMethod;

    @Setter
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingDetailDTO {
        private Integer slot;
        private String name;
        private BigDecimal priceDetail;
    }
}