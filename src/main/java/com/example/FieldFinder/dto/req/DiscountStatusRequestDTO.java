package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class DiscountStatusRequestDTO {
    private String status; // "ACTIVE" | "INACTIVE"
}
