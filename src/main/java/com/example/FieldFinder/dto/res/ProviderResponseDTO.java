package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Provider;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProviderResponseDTO {
    private UUID providerId;
    private UUID userId;
    private String Bank;
    private String cardNumber;

    public static ProviderResponseDTO fromEntity(Provider provider) {
        return new ProviderResponseDTO(
                provider.getProviderId(),
                provider.getUserId(),
                provider.getBank(),
                provider.getCardNumber()
        );
    }
}