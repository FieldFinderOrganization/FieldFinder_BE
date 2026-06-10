package com.example.FieldFinder.entity;


import com.example.FieldFinder.Enum.ModerationSource;
import com.example.FieldFinder.Enum.ReviewStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Reviews")
@Data
@Builder
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ReviewId")
    private UUID reviewId;

    @ManyToOne
    @JoinColumn(name = "PitchId", nullable = false)
    private Pitch pitch;

    @ManyToOne
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "Rating", nullable = false)
    private int rating;

    @Column(name = "Comment")
    private String comment;

    @Column(name = "CreatedAt", nullable = false)
    private LocalDateTime createdAt;

    // --- Kiểm duyệt ---
    // Cột để nullable ở DB để ddl-auto=update thêm cột an toàn trên bảng đã có dữ liệu;
    // ứng dụng luôn set khi tạo mới, dữ liệu cũ được backfill -> APPROVED khi khởi động.
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
