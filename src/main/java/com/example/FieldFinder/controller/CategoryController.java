package com.example.FieldFinder.controller;


import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public CategoryResponseDTO create(@RequestBody CategoryRequestDTO request) {
        return categoryService.createCategory(request);
    }

    @GetMapping
    public List<CategoryResponseDTO> getAll() {
        return categoryService.getAllCategories();
    }

    @GetMapping("/{id}")
    public CategoryResponseDTO getById(@PathVariable Long id) {
        return categoryService.getCategoryById(id);
    }

    @PutMapping("/{id}")
    public CategoryResponseDTO update(@PathVariable Long id, @RequestBody CategoryRequestDTO request) {
        return categoryService.updateCategory(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
    }
}

