package com.example.FieldFinder.dto.res;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderItemResponseDTO {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
