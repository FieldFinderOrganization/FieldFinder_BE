package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.Discount.DiscountStatus;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountRequestDTO {
    private String code;
    private String description;
    private int percentage;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean active;

    // Convert this DTO to an entity
    public Discount toEntity() {
        return Discount.builder()
                .code(this.code)
                .description(this.description)
                .percentage(this.percentage)
                .startDate(this.startDate)
                .endDate(this.endDate)
                .status(this.active ? DiscountStatus.ACTIVE : DiscountStatus.INACTIVE)
                .build();
    }
}
