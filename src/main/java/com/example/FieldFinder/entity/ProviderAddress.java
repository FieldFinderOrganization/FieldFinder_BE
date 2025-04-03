package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "provider_address")
public class ProviderAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID) // Use UUID generation strategy
    @Column(name = "provider_address_id", updatable = false, nullable = false) // Explicit column name
    private UUID providerAddressId;

    @Column(name = "address", nullable = false) // Define address column
    private String address;

    @ManyToOne
    @JoinColumn(name = "provider_id", referencedColumnName = "provider_id", nullable = false) // Foreign key mapping
    private Provider provider;
}
