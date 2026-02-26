package com.example.FieldFinder.dto.req;

import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemRedisDTO implements Serializable {
    private Long productId;
    private String size;
    private int quantity;
    private String addedAt;
}
