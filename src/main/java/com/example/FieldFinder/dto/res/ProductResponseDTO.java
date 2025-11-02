package com.example.FieldFinder.dto.res;


import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductResponseDTO {
    private Long id;
    private String name;
    private String description;
    private String categoryName;
    private Double price;
    private Integer stockQuantity;
    private String imageUrl;
    private String brand;
    private String sex;
}
