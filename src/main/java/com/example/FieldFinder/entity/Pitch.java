package com.example.FieldFinder.entity;


import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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
@NamedEntityGraph(
        name = "Pitch.withProviderDetails",
        attributeNodes = @NamedAttributeNode(value = "providerAddress", subgraph = "providerAddress-subgraph"),
        subgraphs = {
                @NamedSubgraph(name = "providerAddress-subgraph",
                        attributeNodes = @NamedAttributeNode(value = "provider", subgraph = "provider-subgraph")),
                @NamedSubgraph(name = "provider-subgraph",
                        attributeNodes = @NamedAttributeNode("user"))
        }
)
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

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Convert(converter = StringListConverter.class)
    @Column(name = "image_url", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private PitchStatus status = PitchStatus.ACTIVE;

    /**
     * Ngày sân bắt đầu ngưng hoạt động (ngưng theo lịch). Trước ngày này sân vẫn
     * ACTIVE + hiển thị; từ ngày này job tự chuyển INACTIVE và chặn đặt slot.
     * Null = không có lịch ngưng.
     */
    @Column(name = "deactivation_date")
    private LocalDate deactivationDate;

    public enum PitchType {
        FIVE_A_SIDE, SEVEN_A_SIDE, ELEVEN_A_SIDE
    }

    public enum PitchStatus {
        ACTIVE, INACTIVE
    }
}