package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class VerifyOtpRequestDTO {
    private String email;
    private String code;
}
