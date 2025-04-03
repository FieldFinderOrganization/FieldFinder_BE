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
@Table(name = "providers")  // Changed to plural for consistency
@Builder  // Added builder pattern for convenience
public class Provider {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)  // Changed to UUID generation
    @Column(name = "provider_id")  // Added column name with snake_case
    private UUID providerId;

    @Column(name = "user_id", nullable = false)  // Added column name
    private UUID userId;

    @Column(name = "card_number", nullable = true)  // Explicitly marked as nullable
    private String cardNumber;

    @Column(name = "bank", nullable = true)  // Fixed field name to lowercase and marked as nullable
    private String bank;  // Changed from "Bank" to "bank" for Java naming conventions

    @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProviderAddress> addresses = new ArrayList<>();

    // Helper method for bidirectional relationship
    public void addAddress(ProviderAddress address) {
        addresses.add(address);
        address.setProvider(this);
    }

    public void removeAddress(ProviderAddress address) {
        addresses.remove(address);
        address.setProvider(null);
    }
}