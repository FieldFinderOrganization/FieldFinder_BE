package com.example.FieldFinder.entity;


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
}
