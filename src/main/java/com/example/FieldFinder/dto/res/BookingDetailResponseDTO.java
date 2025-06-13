package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.BookingDetail;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class BookingDetailResponseDTO {
    private int slot;
    private String name;
    private BigDecimal priceDetail;
    private UUID pitchId;

    public static BookingDetailResponseDTO fromEntity(BookingDetail detail) {
        BookingDetailResponseDTO dto = new BookingDetailResponseDTO();
        dto.setSlot(detail.getSlot());
        dto.setName(detail.getName());
        dto.setPriceDetail(detail.getPriceDetail());
        dto.setPitchId(detail.getPitch().getPitchId());
        return dto;
    }

}
