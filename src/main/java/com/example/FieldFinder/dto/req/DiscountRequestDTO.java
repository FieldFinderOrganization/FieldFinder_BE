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

    private String scope; // "ALL" | "USER" | "PRODUCT"

    private List<Long> applicableProductIds;
    private List<Long> applicableCategoryIds;

    private int quantity;
    private LocalDate startDate;
    private LocalDate endDate;

    private String status; // "ACTIVE" | "INACTIVE" | "EXPIRED"

    public Discount toEntity() {

        Discount.DiscountStatus statusEnum;
        try {
            statusEnum = Discount.DiscountStatus.valueOf(
                    this.status != null ? this.status : "INACTIVE"
            );
        } catch (Exception e) {
            statusEnum = Discount.DiscountStatus.INACTIVE;
        }

        Discount.DiscountScope scopeEnum;
        try {
            scopeEnum = Discount.DiscountScope.valueOf(
                    this.scope != null && !this.scope.isBlank()
                            ? this.scope
                            : "ALL"
            );
        } catch (Exception e) {
            scopeEnum = Discount.DiscountScope.GLOBAL;
        }

        Discount.DiscountType discountTypeEnum;
        try {
            discountTypeEnum = Discount.DiscountType.valueOf(this.discountType);
        } catch (Exception e) {
            throw new IllegalArgumentException("DiscountType không hợp lệ");
        }

        return Discount.builder()
                .code(this.code)
                .description(this.description)
                .discountType(discountTypeEnum)
                .value(this.value)
                .minOrderValue(this.minOrderValue)
                .maxDiscountAmount(this.maxDiscountAmount)
                .scope(scopeEnum)
                .quantity(this.quantity)
                .startDate(this.startDate)
                .endDate(this.endDate)
                .status(statusEnum)
                .build();
    }
}
