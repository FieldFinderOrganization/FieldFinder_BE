package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public int getTotalStock() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getStockQuantity).sum();
    }

    public int getTotalSold() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getSoldQuantity).sum();
    }
}