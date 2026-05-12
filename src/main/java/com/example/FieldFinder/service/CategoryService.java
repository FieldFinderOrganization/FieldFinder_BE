package com.example.FieldFinder.service;



import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;

import java.util.List;

public interface CategoryService {
    CategoryResponseDTO createCategory(CategoryRequestDTO request);
    List<CategoryResponseDTO> getAllCategories();
    CategoryResponseDTO getCategoryById(Long id);
    CategoryResponseDTO updateCategory(Long id, CategoryRequestDTO request);
    void deleteCategory(Long id);

    List<Long> expandToSuperCategoryDescendants(String name);

    List<Long> expandByProductType(String productType);

    /** Detect productType (SHOES/BAG/TOP/BOTTOM/DRESS/HAT/SANDAL/OTHER) from query keywords. */
    String detectProductTypeFromQuery(String query, List<String> tags, String categoryKeyword);

    /** True nếu product match productType via name/categoryName/tags. */
    boolean productMatchesType(ProductResponseDTO product, String productType);
}
