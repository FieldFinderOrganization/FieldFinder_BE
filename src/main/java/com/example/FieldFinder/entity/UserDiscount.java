package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Builder
@Table(name = "UserDiscounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"UserId", "DiscountId"})
})
public class UserDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DiscountId", nullable = false)
    private Discount discount;

    @Column(name = "IsUsed")
    private boolean isUsed = false;

    @Column(name = "SavedAt")
    private LocalDateTime savedAt = LocalDateTime.now();

    @Column(name = "UsedAt")
    private LocalDateTime usedAt;
}