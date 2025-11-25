package com.example.FieldFinder.dto.req;

import lombok.Data;
import java.util.List;

@Data
public class ProductRequestDTO {
    private Long categoryId;
    private String name;
    private String description;
    private Double price;

    private String imageUrl;
    private String brand;
    private String sex;

    private List<VariantDTO> variants;

    @Data
    public static class VariantDTO {
        private String size;
        private Integer quantity;
    }
}