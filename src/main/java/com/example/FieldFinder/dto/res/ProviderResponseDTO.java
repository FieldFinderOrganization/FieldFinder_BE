package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Provider;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ProviderResponseDTO {
    private Long providerId;
    private String name;
    private String email;
    private String phone;

    public static ProviderResponseDTO fromEntity(Provider provider) {
        return new ProviderResponseDTO(
                provider.getProviderId(),
                provider.getName(),
                provider.getEmail(),
                provider.getPhone()
        );
    }
}