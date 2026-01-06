package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.Product;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountResponseDTO {
    private UUID id;
    private String code;
    private String description;

    private BigDecimal value;
    private String discountType;
    private BigDecimal minOrderValue;
    private BigDecimal maxDiscountAmount;
    private String scope;
    private int quantity;

    private List<Long> applicableProductIds;
    private List<Long> applicableCategoryIds;

    private LocalDate startDate;
    private LocalDate endDate;
    private String status;

    public static DiscountResponseDTO fromEntity(Discount discount) {
        return DiscountResponseDTO.builder()
                .id(discount.getDiscountId())
                .code(discount.getCode())
                .description(discount.getDescription())
                .value(discount.getValue())
                .discountType(discount.getDiscountType() != null ? discount.getDiscountType().name() : null)
                .minOrderValue(discount.getMinOrderValue())
                .maxDiscountAmount(discount.getMaxDiscountAmount())
                .scope(discount.getScope().name())

                .applicableProductIds(discount.getApplicableProducts().stream()
                        .map(Product::getProductId).collect(Collectors.toList()))
                .applicableCategoryIds(discount.getApplicableCategories().stream()
                        .map(Category::getCategoryId).collect(Collectors.toList()))

                .startDate(discount.getStartDate())
                .endDate(discount.getEndDate())
                .quantity(discount.getQuantity())
                .status(discount.getStatus().name())
                .build();
    }
}