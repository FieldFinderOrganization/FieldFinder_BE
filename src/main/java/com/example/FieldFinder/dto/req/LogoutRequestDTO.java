package com.example.FieldFinder.dto.req;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LogoutRequestDTO {
    private String refreshToken;
}
