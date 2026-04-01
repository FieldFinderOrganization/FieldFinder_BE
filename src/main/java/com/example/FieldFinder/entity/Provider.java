package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "providers")
@Builder
public class Provider {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "provider_id")
    private UUID providerId;

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "card_number", nullable = true)
    private String cardNumber;

    @Column(name = "bank", nullable = true)
    private String bank;

    @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProviderAddress> addresses = new ArrayList<>();
}