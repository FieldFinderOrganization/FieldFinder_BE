package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    public CategoryResponseDTO createCategory(CategoryRequestDTO request) {
        if (categoryRepository.existsByName(request.getName())) {
            throw new RuntimeException("Category already exists!");
        }

        Category parent = null;
        if (request.getParentId() != null) {
            parent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new RuntimeException("Cannot find parent category!"));
        }

        Category category = Category.builder()
                .name(request.getName())
                .description(request.getDescription())
                .parent(parent)
                .build();

        categoryRepository.save(category);
        return mapToResponse(category);
    }

    @Override
    public List<CategoryResponseDTO> getAllCategories() {
        return categoryRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public CategoryResponseDTO getCategoryById(Long id) {
        return categoryRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new RuntimeException("Cannot find category!"));
    }

    @Override
    public CategoryResponseDTO updateCategory(Long id, CategoryRequestDTO request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cannot find category"));

        Category parent = null;
        if (request.getParentId() != null) {
            parent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new RuntimeException("Cannot find parent category!"));
        }

        category.setName(request.getName());
        category.setDescription(request.getDescription());
        category.setParent(parent);
        categoryRepository.save(category);

        return mapToResponse(category);
    }

    @Override
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    }

    private CategoryResponseDTO mapToResponse(Category category) {
        return CategoryResponseDTO.builder()
                .id(category.getCategoryId())
                .name(category.getName())
                .description(category.getDescription())
                .parentName(category.getParent() != null ? category.getParent().getName() : null)
                .build();
    }
}
