package com.example.FieldFinder.entity;

import com.example.FieldFinder.converter.StringSetConverter;
import jakarta.persistence.*;
import lombok.*;
import com.example.FieldFinder.util.DiscountEligibilityUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.math.BigDecimal;

@Entity
@Table(name = "products")
/** Chỉ fetch 1 bag (variants) + category. discounts dùng @BatchSize — tránh MultipleBagFetchException. */
@NamedEntityGraph(
        name = "Product.listView",
        attributeNodes = {
                @NamedAttributeNode("category"),
                @NamedAttributeNode("variants")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long productId;

    // Quan hệ JPA loại khỏi equals/hashCode/toString — tránh hash lan sang
    // category.parent (proxy LAZY) khi Hibernate dirty-check (xem OrderItem).
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    private String name;
    private String description;
    private Double price;
    private String imageUrl;
    private String brand;
    private String sex;

    /**
     * Màu chủ đạo CHUẨN (canonical VN, vd "đen", "trắng", "xanh dương) — nguồn màu đáng tin cho
     * tìm kiếm/xếp hạng. AI seed lúc tạo sản phẩm, admin kiểm duyệt/sửa tay. Khác với {@link #tags}
     * (gồm cả màu phụ/accent nhiễu). null = chưa gán.
     */
    @Column(name = "dominant_color", length = 32)
    private String dominantColor;

    /**
     * Tập màu CHÍNH canonical (đã sạch, primary trước) cho sp đa màu (vd giày đen/trắng 50/50 →
     * ["đen","trắng"]). Chỉ màu phủ diện tích đáng kể, cap ~3 — KHÁC {@link #tags} (gồm accent/logo).
     * {@link #dominantColor} = phần tử đầu. Match phân tầng: dominant=2 > trong colors=1 > 0.
     */
    @Convert(converter = StringSetConverter.class)
    @Column(name = "colors", columnDefinition = "TEXT")
    @Builder.Default
    private Set<String> colors = new HashSet<>();

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Convert(converter = StringSetConverter.class)
    @Column(name = "tags", columnDefinition = "TEXT")
    @Builder.Default
    private Set<String> tags = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<ProductVariant> variants;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "image_phash")
    private Long imagePhash;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<ProductDiscount> discounts = new ArrayList<>();


    @Transient
    private Integer onSalePercent;

    @Transient
    private Double salePrice;


    public double[] getEmbeddingArray() {
        if (embedding == null || embedding.isEmpty()) return new double[0];
        try {
            String clean = embedding.replace("[", "").replace("]", "");
            return Arrays.stream(clean.split(","))
                    .mapToDouble(Double::parseDouble)
                    .toArray();
        } catch (Exception e) {
            return new double[0];
        }
    }

    public int getTotalSold() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getSoldQuantity).sum();
    }

    public Double getSalePrice() {
        if (this.salePrice != null) {
            return this.salePrice;
        }
        return this.price;
    }

    public Integer getOnSalePercent() {
        if (this.onSalePercent != null) {
            return this.onSalePercent;
        }

        if (this.price == null || this.price == 0) return 0;

        Double finalPrice = getSalePrice();
        if (finalPrice == null || finalPrice.equals(this.price)) return 0;

        double totalReduction = this.price - finalPrice;
        if (totalReduction <= 0) return 0;

        int pct = (int) Math.round((totalReduction / this.price) * 100);
        return Math.max(pct, 1);
    }

    public void calculateSalePriceForUser(List<Discount> availableDiscounts) {
        if (this.price == null) {
            this.salePrice = 0.0;
            return;
        }

        // BEST-WINS: thử từng mã item-level trên GIÁ GỐC, giữ giá thấp nhất.
        // Tránh stack 2 mã cùng item (CATEGORY + SPECIFIC_PRODUCT) → cộng dồn discount.
        double bestPrice = this.price;
        if (availableDiscounts != null && !availableDiscounts.isEmpty()) {
            for (Discount d : availableDiscounts) {
                if (d.getScope() == Discount.DiscountScope.GLOBAL) continue;
                if (DiscountEligibilityUtil.isEligibleForProductPreview(d, this)) {
                    double candidate = applyDiscountLogic(this.price, d);
                    if (candidate < bestPrice) bestPrice = candidate;
                }
            }
        }

        this.salePrice = Math.max(0, bestPrice);

        if (this.price > 0) {
            double totalReduction = this.price - this.salePrice;
            if (totalReduction <= 0) {
                this.onSalePercent = 0;
            } else {
                int pct = (int) Math.round((totalReduction / this.price) * 100);
                this.onSalePercent = Math.max(pct, 1);
            }
        } else {
            this.onSalePercent = 0;
        }
    }

    private double applyDiscountLogic(double currentPrice, Discount d) {
        double reduction = 0.0;
        BigDecimal valBd = d.getValue();
        double value = valBd != null ? valBd.doubleValue() : 0.0;

        if (d.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
            reduction = value;
        } else {
            reduction = currentPrice * (value / 100.0);
        }

        if (d.getMaxDiscountAmount() != null) {
            double maxLimit = d.getMaxDiscountAmount().doubleValue();
            if (maxLimit > 0) {
                reduction = Math.min(reduction, maxLimit);
            }
        }
        return currentPrice - reduction;
    }

    public Double getEffectivePrice() {
        return getSalePrice();
    }
}