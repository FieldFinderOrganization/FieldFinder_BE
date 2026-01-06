package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.UserDiscount;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
public class UserDiscountResponseDTO {
    private UUID userDiscountId;
    private String discountCode;
    private String description;
    private String status;
    private BigDecimal value;
    private String type;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal minOrderValue;

    private BigDecimal maxDiscountAmount;
    private String scope;
    private List<Long> applicableProductIds;
    private List<Long> applicableCategoryIds;

    public static UserDiscountResponseDTO fromEntity(UserDiscount userDiscount) {
        String calculatedStatus = "AVAILABLE";
        if (userDiscount.isUsed()) {
            calculatedStatus = "USED";
        } else if (LocalDate.now().isAfter(userDiscount.getDiscount().getEndDate())) {
            calculatedStatus = "EXPIRED";
        }

        return UserDiscountResponseDTO.builder()
                .userDiscountId(userDiscount.getId())
                .discountCode(userDiscount.getDiscount().getCode())
                .description(userDiscount.getDiscount().getDescription())
                .status(calculatedStatus)
                .value(userDiscount.getDiscount().getValue())
                .type(userDiscount.getDiscount().getDiscountType().name())
                .startDate(userDiscount.getDiscount().getStartDate())
                .endDate(userDiscount.getDiscount().getEndDate())
                .minOrderValue(userDiscount.getDiscount().getMinOrderValue())

                .maxDiscountAmount(userDiscount.getDiscount().getMaxDiscountAmount())
                .scope(userDiscount.getDiscount().getScope().name())
                .applicableProductIds(
                        userDiscount.getDiscount().getApplicableProducts() != null
                                ? userDiscount.getDiscount().getApplicableProducts().stream()
                                .map(Product::getProductId)
                                .collect(Collectors.toList())
                                : new ArrayList<>()
                )
                .applicableCategoryIds(
                        userDiscount.getDiscount().getApplicableCategories() != null
                                ? userDiscount.getDiscount().getApplicableCategories().stream()
                                .map(Category::getCategoryId)
                                .collect(Collectors.toList())
                                : new ArrayList<>()
                )
                .build();
    }
}