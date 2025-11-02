package com.example.FieldFinder.dto.res;

import lombok.*;

import java.math.BigDecimal;
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
    private Double priceAtTime;
}
