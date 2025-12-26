package com.example.FieldFinder.dto.res;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemResponseDTO {
    private Long id;
    private Long cartId;
    private Long productId;
    private String productName;
    private String imageUrl;
    private int quantity;
    private String size;

    private Double originalPrice;
    private Double priceAtTime;

    private List<DiscountDTO> appliedDiscounts;

    @Data
    @Builder
    public static class DiscountDTO {
        private String code;
        private Double value;
        private String discountType;
        private Double maxDiscountAmount;
        private Long id;
    }
}