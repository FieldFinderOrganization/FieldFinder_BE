package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Discount;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountResponseDTO {
    private UUID id;
    private String code;
    private String description;
    private int percentage;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;

    // Convert an entity to a response DTO
    public static DiscountResponseDTO fromEntity(Discount discount) {
        return DiscountResponseDTO.builder()
                .id(discount.getDiscountId())
                .code(discount.getCode())
                .description(discount.getDescription())
                .percentage(discount.getPercentage())
                .startDate(discount.getStartDate())
                .endDate(discount.getEndDate())
                .status(discount.getStatus().name())
                .build();
    }
}
