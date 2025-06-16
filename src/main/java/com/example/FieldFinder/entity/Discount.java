package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "Percentage", nullable = false)
    private int percentage;

    @Column(name = "StartDate", nullable = false)
    private LocalDate startDate;

    @Column(name = "EndDate", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false)
    private DiscountStatus status;

    public enum DiscountStatus {
        ACTIVE, INACTIVE, EXPIRED
    }
}
