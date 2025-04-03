package com.example.FieldFinder.dto.req;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProviderRequestDTO {
    private UUID userId;
    private String cardNumber;
    private String bank;
}
