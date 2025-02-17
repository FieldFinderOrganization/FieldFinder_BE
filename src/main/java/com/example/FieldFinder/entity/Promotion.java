package com.example.FieldFinder.entity;

package com.footballbooking.application.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Promotions")
@Data
@Builder
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "PromotionId")
    private UUID promotionId;

    @ManyToOne
    @JoinColumn(name = "OwnerId", nullable = false)
    private User owner;

    @Column(name = "Code", nullable = false, unique = true)
    private String code;

    @Column(name = "DiscountPercentage", nullable = false)
    private Double discountPercentage;

    @Column(name = "StartDate", nullable = false)
    private LocalDate startDate;

    @Column(name = "EndDate", nullable = false)
    private LocalDate endDate;
}
