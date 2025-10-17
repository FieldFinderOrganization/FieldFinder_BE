package com.example.FieldFinder.dto.req;

import lombok.Data;

@Data
public class CategoryRequestDTO {
    private String name;
    private String description;
    private Long parentId;
}
