package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.Enum.CategoryType;
import lombok.Data;

@Data
public class CategoryRequestDTO {
    private String name;
    private String description;
    private Long parentId;
    private CategoryType categoryType = CategoryType.STANDARD;
}
