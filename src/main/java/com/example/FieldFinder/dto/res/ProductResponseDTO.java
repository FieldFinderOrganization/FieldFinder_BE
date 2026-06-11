package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Product;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductResponseDTO{

    private Long id;
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private Double price;

    private Integer salePercent;
    private Double salePrice;

    private String imageUrl;
    private String brand;
    private String sex;

    /** Màu chủ đạo canonical (vd "đen") — ranker/ML dùng làm "màu thật" để xếp hạng. */
    private String dominantColor;

    /** Tập màu chính canonical (sp đa màu) — dùng cho match phân tầng (xem {@link #colorRank}). */
    private Set<String> colors;

    private Set<String> tags;

    /**
     * Mức khớp màu canonical user/ảnh nêu: 2 = trùng dominantColor (màu thuần), 1 = nằm trong
     * colors (sp đa màu, vd đen/trắng 50/50), 0 = không khớp. Cho "màu thuần lên đầu, nửa-màu sau".
     */
    public int colorRank(String canonicalQuery) {
        if (canonicalQuery == null || canonicalQuery.isBlank()) return 0;
        if (dominantColor != null && canonicalQuery.equalsIgnoreCase(dominantColor)) return 2;
        if (colors != null) {
            for (String c : colors) {
                if (c != null && canonicalQuery.equalsIgnoreCase(c)) return 1;
            }
        }
        return 0;
    }

    private List<VariantDTO> variants;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VariantDTO {
        private String size;
        private Integer quantity;
        private Integer stockTotal;
    }

    private Integer totalSold;

    private List<String> appliedDiscountCodes;

    /** Mã GLOBAL eligible cho user nhưng KHÔNG áp vào salePrice. FE dùng để gợi ý ở checkout. */
    private List<String> availableGlobalCodes;

    public static ProductResponseDTO fromEntity(Product product) {
        return ProductResponseDTO.builder()
                .id(product.getProductId())
                .name(product.getName())
                .description(product.getDescription())
                .categoryId(product.getCategory() != null ? product.getCategory().getCategoryId() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .price(product.getPrice())

                .salePrice(product.getSalePrice())
                .salePercent(product.getOnSalePercent())

                .imageUrl(product.getImageUrl())
                .brand(product.getBrand())
                .sex(product.getSex())
                .dominantColor(product.getDominantColor())
                .colors(product.getColors())
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