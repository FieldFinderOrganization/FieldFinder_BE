package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "user_providers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_provider_name_uid",
                columnNames = {"provider_name", "provider_uid"}
        )
)
public class UserProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 50)
    private ProviderName providerName;

    @Column(name = "provider_uid", nullable = false, length = 255)
    private String providerUid;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    public enum ProviderName {
        GOOGLE,
        FACEBOOK,
        FIREBASE
    }
}
