package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.ProviderAddress;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
public class ProviderAddressResponseDTO {
    private UUID providerAddressId;
    private String address;

    public static ProviderAddressResponseDTO fromEntity(ProviderAddress providerAddress) {
        return new ProviderAddressResponseDTO(
                providerAddress.getProviderAddressId(),
                providerAddress.getAddress()
        );
    }
}
