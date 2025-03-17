package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "provider_address")
public class ProviderAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long providerAddressId;

    private String address;

    @ManyToOne
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;
}
