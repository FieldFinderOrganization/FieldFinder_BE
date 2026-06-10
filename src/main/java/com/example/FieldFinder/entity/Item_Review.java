package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.ModerationSource;
import com.example.FieldFinder.Enum.ReviewStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "Item_Review")
public class Item_Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ReviewId", updatable = false, nullable = false)
    private Long reviewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProductId", nullable = false)
    private Product product;

    @Column(name = "Rating", nullable = false)
    private int rating;

    @Column(name = "Comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // --- Kiểm duyệt ---
    // Cột nullable ở DB cho ddl-auto=update an toàn; app luôn set khi tạo mới,
    // dữ liệu cũ được backfill -> APPROVED khi khởi động.
    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "ModerationReason", columnDefinition = "TEXT")
    private String moderationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "ModerationSource", length = 20)
    private ModerationSource moderationSource;

    @Column(name = "ModeratedAt")
    private LocalDateTime moderatedAt;
}
