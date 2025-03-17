package com.example.FieldFinder.dto.req;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProviderAddressRequestDTO {
    private Long providerId;
    private String address;
}
