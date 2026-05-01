package com.example.FieldFinder.dto.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProviderAddressRequestDTO {
    private UUID providerId;
    private String address;
}
