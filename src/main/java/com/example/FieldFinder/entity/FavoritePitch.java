package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sân yêu thích của user. Entity phẳng (chỉ giữ 2 UUID, không @ManyToOne) để
 * tránh Lombok @Data/hashCode walk graph khi flush. Unique(userId, pitchId)
 * đảm bảo không favorite trùng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "FavoritePitches",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_favorite_user_pitch",
                columnNames = {"UserId", "PitchId"}
        )
)
public class FavoritePitch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "UserId", nullable = false)
    private UUID userId;

    @Column(name = "PitchId", nullable = false)
    private UUID pitchId;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
