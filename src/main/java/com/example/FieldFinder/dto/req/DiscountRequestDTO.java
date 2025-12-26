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

    private String scope;        // "GLOBAL" | "SPECIFIC_PRODUCT" | "CATEGORY"

    // SỬA 1: Đổi tên biến để khớp với JSON gửi lên ("applicableProductIds")
    private List<Long> applicableProductIds;

    // SỬA 2: Đổi tên biến để khớp với JSON gửi lên ("applicableCategoryIds")
    private List<Long> applicableCategoryIds;

    private int quantity;
    private LocalDate startDate;
    private LocalDate endDate;

    // SỬA 3: Đổi từ boolean isActive sang String status để nhận giá trị "ACTIVE" từ JSON
    private String status;

    public Discount toEntity() {
        // Logic parse status từ String sang Enum
        Discount.DiscountStatus statusEnum;
        try {
            statusEnum = Discount.DiscountStatus.valueOf(this.status);
        } catch (Exception e) {
            // Fallback nếu null hoặc sai format
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
                .status(statusEnum) // Sử dụng enum đã parse
                .build();
    }
}