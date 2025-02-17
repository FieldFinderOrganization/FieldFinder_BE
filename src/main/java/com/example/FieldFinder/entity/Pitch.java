package com.example.FieldFinder.entity;

package com.footballbooking.application.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Pitches")
@Data
@Builder
public class Pitch {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "PitchId")
    private UUID pitchId;

    @ManyToOne
    @JoinColumn(name = "OwnerId", nullable = false)
    private User owner;

    @Column(name = "Name", nullable = false)
    private String name;

    @Column(name = "Address", nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", nullable = false)
    private PitchType type;

    @Column(name = "Price", nullable = false)
    private BigDecimal price;

    @Column(name = "ImageUrl")
    private String imageUrl;

    @Column(name = "Description")
    private String description;

    public enum PitchType {
        FIVE_A_SIDE, SEVEN_A_SIDE, ELEVEN_A_SIDE
    }
}
