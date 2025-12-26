package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Discounts")
@Data
@Builder
public class Discount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "DiscountId")
    private UUID discountId;

    @Column(name = "Code", nullable = false, unique = true)
    private String code;

    @Column(name = "Description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "DiscountType", nullable = false)
    private DiscountType discountType; // PERCENTAGE, FIXED_AMOUNT

    @Column(name = "Value", nullable = false)
    private BigDecimal value;

    @Column(name = "MinOrderValue")
    private BigDecimal minOrderValue;

    @Column(name = "MaxDiscountAmount")
    private BigDecimal maxDiscountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "Scope", nullable = false)
    private DiscountScope scope;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "Discount_Product_Applicable",
            joinColumns = @JoinColumn(name = "DiscountId"),
            inverseJoinColumns = @JoinColumn(name = "ProductId")
    )
    private Set<Product> applicableProducts = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "Discount_Category_Applicable",
            joinColumns = @JoinColumn(name = "DiscountId"),
            inverseJoinColumns = @JoinColumn(name = "CategoryId")
    )
    private Set<Category> applicableCategories = new HashSet<>();

    @Column(name = "Quantity", nullable = false)
    private int quantity;

    @Column(name = "StartDate", nullable = false)
    private LocalDate startDate;

    @Column(name = "EndDate", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false)
    private DiscountStatus status;

    public enum DiscountStatus { ACTIVE, INACTIVE, EXPIRED }
    public enum DiscountType { PERCENTAGE, FIXED_AMOUNT }
    public enum DiscountScope { GLOBAL, SPECIFIC_PRODUCT, CATEGORY }
}