package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class ProductRequestDTO {
    private Long categoryId;
    private String name;
    private String description;
    private Double price;
    private Integer stockQuantity;
    private String imageUrl;
    private String brand;
    private String sex;
}