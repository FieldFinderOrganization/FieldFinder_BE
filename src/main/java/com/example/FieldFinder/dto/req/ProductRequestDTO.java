package com.example.FieldFinder.dto.req;

import lombok.Data;
import java.util.List;
import java.util.Set;

@Data
public class ProductRequestDTO {
    private Long categoryId;
    private String name;
    private String description;
    private Double price;

    private String imageUrl;
    private String brand;
    private String sex;

    /** Màu chủ đạo canonical (vd "đen") — admin set khi duyệt; null = để AI seed. */
    private String dominantColor;

    /** Tập màu chính (sp đa màu, vd ["đen","trắng"]) — admin set khi duyệt; null = để AI seed. */
    private Set<String> colors;

    private Set<String> tags;

    private List<VariantDTO> variants;

    @Data
    public static class VariantDTO {
        private String size;
        private Integer quantity;
    }
}