package com.example.FieldFinder.dto.req;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemRequestDTO {
    private Long productId;
    private int quantity;
    private String size;
}
