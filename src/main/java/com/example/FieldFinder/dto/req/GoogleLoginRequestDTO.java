package com.example.FieldFinder.dto.req;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GoogleLoginRequestDTO {
    /** Google ID Token lấy từ Google Sign-In SDK trên mobile/web */
    private String idToken;
}
