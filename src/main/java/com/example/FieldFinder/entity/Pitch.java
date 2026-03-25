package com.example.FieldFinder.entity;


import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    @JoinColumn(name = "ProviderAddressId", nullable = false)
    private ProviderAddress providerAddress;

    @Column(name = "Name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", nullable = false)
    private PitchType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "Environment", nullable = false)
    private PitchEnvironment environment;

    @Column(name = "Price", nullable = false)
    private BigDecimal price;

    @Column(name = "Description")
    private String description;

    @Convert(converter = StringListConverter.class)
    @Column(name = "image_url", columnDefinition = "TEXT")
    private List<String> imageUrls = new ArrayList<>();

    public enum PitchType {
        FIVE_A_SIDE, SEVEN_A_SIDE, ELEVEN_A_SIDE
    }
}
