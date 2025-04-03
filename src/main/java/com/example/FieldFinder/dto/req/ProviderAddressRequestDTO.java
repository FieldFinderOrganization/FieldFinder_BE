package com.example.FieldFinder.dto.req;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProviderAddressRequestDTO {
    private UUID providerId;
    private String address;
}
