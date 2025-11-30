package com.example.FieldFinder.dto.res;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class ProductResponseDTO {
    private Long id;
    private String name;
    private String description;
    private String categoryName;
    private Double price;

    private String imageUrl;
    private String brand;
    private String sex;

    private List<String> tags;

    private List<VariantDTO> variants;

    public Integer getStockQuantity() {
        if (variants == null || variants.isEmpty()) {
            return 0;
        }
        return variants.stream()
                .mapToInt(VariantDTO::getQuantity)
                .sum();
    }

    @Data
    @Builder
    public static class VariantDTO {
        private String size;
        private Integer quantity;
        private Integer stockTotal;
    }
}