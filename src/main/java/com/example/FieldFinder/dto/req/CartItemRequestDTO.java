package com.example.FieldFinder.dto.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemRequestDTO {
    private Long cartId;
    private UUID userId;
    private Long productId;
    private int quantity;
    private String size;
}
