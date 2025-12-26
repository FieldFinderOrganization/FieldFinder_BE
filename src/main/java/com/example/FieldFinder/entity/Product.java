package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;
import java.math.BigDecimal; // Import quan trọng

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long productId;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;
    private String name;
    private String description;
    private Double price;
    private String imageUrl;
    private String brand;
    private String sex;
    private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductVariant> variants;

    @Column(columnDefinition = "TEXT")
    private String embedding;

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

    // --- CÁC TRƯỜNG TRANSIENT ĐỂ TÍNH GIÁ ---
    @Transient
    private Integer onSalePercent;

    @Transient
    private Double salePrice;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductDiscount> discounts = new ArrayList<>();

    /**
     * Tính toán giá bán cuối cùng (Sale Price) bằng cách áp dụng tuần tự tất cả các mã giảm giá khả dụng.
     */
    public Double getSalePrice() {
        if (this.price == null) return 0.0;

        // Khởi tạo giá hiện tại bằng giá gốc
        double currentPrice = this.price;
        LocalDate now = LocalDate.now();

        // Nếu danh sách null hoặc rỗng, trả về giá gốc ngay
        if (this.discounts == null || this.discounts.isEmpty()) {
            return currentPrice;
        }

        for (ProductDiscount pd : this.discounts) {
            Discount d = pd.getDiscount();
            if (d == null) continue;

            // Kiểm tra trạng thái Active
            boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;

            // Kiểm tra ngày bắt đầu (Nếu null coi như đã bắt đầu)
            boolean isStarted = d.getStartDate() == null || !now.isBefore(d.getStartDate());

            // Kiểm tra ngày kết thúc (Nếu null coi như không bao giờ hết hạn)
            boolean isNotExpired = d.getEndDate() == null || !now.isAfter(d.getEndDate());

            if (isActive && isStarted && isNotExpired) {
                double reduction = 0.0;
                double value = d.getValue() != null ? d.getValue().doubleValue() : 0.0;

                // Xử lý logic tính tiền giảm
                if (value > 100) {
                    // Trường hợp value > 100: Coi là giảm tiền trực tiếp (VNĐ)
                    reduction = value;
                } else {
                    // Trường hợp value <= 100: Coi là giảm phần trăm (%)
                    // Tính phần trăm dựa trên giá HIỆN TẠI (giá sau khi đã trừ các mã trước đó)
                    reduction = currentPrice * (value / 100.0);
                }

                // Kiểm tra và áp dụng giới hạn giảm tối đa (Max Discount Amount)
                if (d.getMaxDiscountAmount() != null) {
                    double maxLimit = d.getMaxDiscountAmount().doubleValue();
                    if (maxLimit > 0) {
                        reduction = Math.min(reduction, maxLimit);
                    }
                }

                // Trừ số tiền giảm vào giá hiện tại
                currentPrice -= reduction;
            }
        }

        // Đảm bảo giá không âm
        return Math.max(0, currentPrice);
    }

    /**
     * Tính toán % tổng thực tế dựa trên giá gốc và giá cuối cùng.
     * Để hiển thị badge (ví dụ: "-25%")
     */
    public Integer getOnSalePercent() {
        if (this.price == null || this.price == 0) return 0;

        Double finalPrice = getSalePrice(); // Lấy giá đã tính toán ở trên
        if (finalPrice == null) return 0;

        double totalReduction = this.price - finalPrice;
        if (totalReduction <= 0) return 0;

        // Công thức: (Tổng tiền giảm / Giá gốc) * 100
        return (int) Math.round((totalReduction / this.price) * 100);
    }

    public Double getEffectivePrice() {
        return getSalePrice();
    }
}