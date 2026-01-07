package com.example.FieldFinder.dto.req;


import lombok.Data;

@Data
public class OrderItemRequestDTO {
    private Long productId;
    private Integer quantity;
    private String size;
}
