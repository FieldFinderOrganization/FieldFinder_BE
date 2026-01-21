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

    private Integer salePercent; // % giảm thực tế
    private Double salePrice;    // Giá sau khi giảm hết các mã

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
        private Integer quantity; // Số lượng tồn kho hiện tại
        private Integer stockTotal; // Tổng số lượng nhập
    }

    private Integer totalSold;

    /**
     * Helper method để chuyển đổi từ Entity sang DTO.
     * Gọi đúng các hàm tính toán logic giá (getSalePrice, getOnSalePercent).
     */
    public static ProductResponseDTO fromEntity(Product product) {
        return ProductResponseDTO.builder()
                .id(product.getProductId())
                .name(product.getName())
                .description(product.getDescription())
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .price(product.getPrice())

                // --- GỌI LOGIC TÍNH TOÁN CỦA ENTITY ---
                .salePrice(product.getSalePrice())
                .salePercent(product.getOnSalePercent())
                // --------------------------------------

                .imageUrl(product.getImageUrl())
                .brand(product.getBrand())
                .sex(product.getSex())
                .tags(product.getTags())
                .totalSold(product.getTotalSold())
                .variants(product.getVariants() != null ? product.getVariants().stream()
                        .map(v -> VariantDTO.builder()
                                .size(v.getSize())
                                .quantity(v.getAvailableQuantity()) // Chú ý: dùng availableQuantity từ ProductVariant
                                .stockTotal(v.getStockQuantity())
                                .build())
                        .collect(Collectors.toList()) : null)
                .build();
    }
}