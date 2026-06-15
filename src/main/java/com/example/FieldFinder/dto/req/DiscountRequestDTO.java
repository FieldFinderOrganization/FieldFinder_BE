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

    private String scope; // "GLOBAL" | "SPECIFIC_PRODUCT" | "CATEGORY" (null/blank/invalid -> GLOBAL)

    private List<Long> applicableProductIds;
    private List<Long> applicableCategoryIds;

    private int quantity;
    private LocalDate startDate;
    private LocalDate endDate;

    private String status; // "ACTIVE" | "INACTIVE" | "EXPIRED"

    private String minTier; // null/blank/"ALL" = mọi user | "MEMBER" | "SILVER" | "GOLD" | "DIAMOND"

    private Integer pointCost; // null = không bán bằng điểm; có giá = chỉ đổi qua điểm thưởng

    /** Parse scope an toàn: null/blank/giá trị lạ -> GLOBAL. Dùng chung create + update. */
    public Discount.DiscountScope parseScope() {
        if (this.scope == null || this.scope.isBlank()) {
            return Discount.DiscountScope.GLOBAL;
        }
        try {
            return Discount.DiscountScope.valueOf(this.scope.toUpperCase());
        } catch (Exception e) {
            return Discount.DiscountScope.GLOBAL;
        }
    }

    public com.example.FieldFinder.Enum.UserTier parseMinTier() {
        if (this.minTier == null || this.minTier.isBlank() || "ALL".equalsIgnoreCase(this.minTier)) {
            return null;
        }
        try {
            return com.example.FieldFinder.Enum.UserTier.valueOf(this.minTier.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("minTier không hợp lệ: " + this.minTier);
        }
    }

    public Discount toEntity() {

        Discount.DiscountStatus statusEnum;
        try {
            statusEnum = Discount.DiscountStatus.valueOf(
                    this.status != null ? this.status : "INACTIVE"
            );
        } catch (Exception e) {
            statusEnum = Discount.DiscountStatus.INACTIVE;
        }

        Discount.DiscountScope scopeEnum = parseScope();

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
                .minTier(parseMinTier())
                .pointCost(this.pointCost)
                .build();
    }
}
