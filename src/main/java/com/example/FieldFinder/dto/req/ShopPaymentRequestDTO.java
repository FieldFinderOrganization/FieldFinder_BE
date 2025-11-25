package com.example.FieldFinder.dto.req;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class ShopPaymentRequestDTO {
    private UUID userId;
    private BigDecimal amount;
    private String description;
    private String paymentMethod;
    private List<CartItemDTO> items;

    @Data
    public static class CartItemDTO {
        private Long productId;
        private int quantity;
        private String size;
    }
}