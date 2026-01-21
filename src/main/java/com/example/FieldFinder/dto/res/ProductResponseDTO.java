package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Product;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
public class ProductResponseDTO {
    private Long id;
    private String name;
    private String description;
    private String categoryName;
    private Double price;

    private Integer salePercent;
    private Double salePrice;

    private String imageUrl;
    private String brand;
    private String sex;

    private Set<String> tags;

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

    private Integer totalSold;

    public static ProductResponseDTO fromEntity(Product product) {
        return ProductResponseDTO.builder()
                .id(product.getProductId())
                .name(product.getName())
                .description(product.getDescription())
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .price(product.getPrice())

                .salePrice(product.getSalePrice())
                .salePercent(product.getOnSalePercent())

                .imageUrl(product.getImageUrl())
                .brand(product.getBrand())
                .sex(product.getSex())
                .tags(product.getTags())
                .totalSold(product.getTotalSold())
                .variants(product.getVariants() != null ? product.getVariants().stream()
                        .map(v -> VariantDTO.builder()
                                .size(v.getSize())
                                .quantity(v.getAvailableQuantity())
                                .stockTotal(v.getStockQuantity())
                                .build())
                        .collect(Collectors.toList()) : null)
                .build();
    }
}