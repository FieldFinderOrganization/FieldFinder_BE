package com.example.FieldFinder.dto.res;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartResponseDTO {
    private List<CartItemDetail> items;
    private Double totalCartPrice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemDetail {
        private Long productId;
        private String productName;
        private String imageUrl;
        private String brand;
        private String sex;
        private String size;

        private Double originalPrice;
        private Double unitPrice;
        private Double totalPrice;

        private Integer quantity;
        private Integer stockAvailable;

        private Integer salePercent;

        private Long categoryId;
        private List<String> appliedDiscountCodes;
    }
}