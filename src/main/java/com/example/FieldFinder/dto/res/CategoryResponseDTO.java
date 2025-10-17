package com.example.FieldFinder.dto.res;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponseDTO {
    private Long id;
    private String name;
    private String description;
    private String parentName;
}
