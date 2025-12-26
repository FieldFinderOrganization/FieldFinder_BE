package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.UserDiscount;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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
                .build();
    }
}