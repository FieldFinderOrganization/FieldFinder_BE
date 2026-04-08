package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthTokenResponseDTO {
    private String accessToken;
    private String refreshToken;
    private UserResponseDTO user;
}
