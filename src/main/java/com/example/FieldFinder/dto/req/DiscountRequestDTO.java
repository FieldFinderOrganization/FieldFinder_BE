package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.entity.Discount;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class DiscountRequestDTO {
    private String code;
    private String description;

    private String discountType; // "PERCENTAGE" | "FIXED_AMOUNT"
    private BigDecimal value;
    private BigDecimal minOrderValue;
    private BigDecimal maxDiscountAmount;

    private String scope;

    private List<Long> applicableProductIds;

    private List<Long> applicableCategoryIds;

    private int quantity;
    private LocalDate startDate;
    private LocalDate endDate;

    private String status;

    public Discount toEntity() {
        Discount.DiscountStatus statusEnum;
        try {
            statusEnum = Discount.DiscountStatus.valueOf(this.status);
        } catch (Exception e) {
            statusEnum = Discount.DiscountStatus.INACTIVE;
        }

        return Discount.builder()
                .code(this.code)
                .description(this.description)
                .discountType(Discount.DiscountType.valueOf(this.discountType))
                .value(this.value)
                .minOrderValue(this.minOrderValue)
                .maxDiscountAmount(this.maxDiscountAmount)
                .scope(Discount.DiscountScope.valueOf(this.scope))
                .quantity(this.quantity)
                .startDate(this.startDate)
                .endDate(this.endDate)
                .status(statusEnum)
                .build();
    }
}