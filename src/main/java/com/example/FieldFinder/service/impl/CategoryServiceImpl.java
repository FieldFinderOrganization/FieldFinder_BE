package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.Enum.CategoryType;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                .categoryType(request.getCategoryType() != null ? request.getCategoryType() : CategoryType.STANDARD)
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
        if (request.getCategoryType() != null) {
            category.setCategoryType(request.getCategoryType());
        }
        categoryRepository.save(category);

        return mapToResponse(category);
    }

    @Override
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    }

    private static final Map<String, List<String>> CATEGORY_ALIASES = Map.of(
            "FOOTWEAR",  List.of("giày", "dép", "shoe", "footwear", "sneaker", "boot"),
            "CLOTHING",  List.of("áo", "quần", "váy", "đồ", "clothing", "apparel", "shirt", "pants"),
            "ACCESSORY", List.of("balo", "ba lô", "nón", "mũ", "túi", "phụ kiện", "accessory", "bag", "hat", "cap")
    );

    private static final Map<String, List<String>> PRODUCT_TYPE_ALIASES = Map.of(
            "SHOES",   List.of("giày", "shoe", "sneaker", "boot"),
            "SANDAL",  List.of("dép", "sandal", "slipper"),
            "TOP",     List.of("áo", "shirt", "tee", "hoodie", "jacket", "polo"),
            "BOTTOM",  List.of("quần", "short", "pants", "trousers", "jeans", "jogger"),
            "DRESS",   List.of("váy", "đầm", "dress", "skirt"),
            "BAG",     List.of("balo", "ba lô", "túi", "bag", "backpack"),
            "HAT",     List.of("nón", "mũ", "cap", "hat", "beanie"),
            "OTHER",   List.of("phụ kiện", "accessory", "kính", "vớ", "găng")
    );

    @Override
    public List<Long> expandToSuperCategoryDescendants(String name) {
        if (name == null || name.isBlank()) return Collections.emptyList();
        String needle = name.toLowerCase().trim();
        String enumKey = name.trim().toUpperCase();

        List<Category> all = categoryRepository.findAll();

        Category match = null;

        // Tier 1: alias map cho enum FOOTWEAR/CLOTHING/ACCESSORY
        if (CATEGORY_ALIASES.containsKey(enumKey)) {
            for (String kw : CATEGORY_ALIASES.get(enumKey)) {
                for (Category c : all) {
                    if (c.getName() != null && c.getName().toLowerCase().contains(kw)) {
                        if (c.getCategoryType() != CategoryType.BRAND) {
                            match = c;
                            break;
                        }
                    }
                }
                if (match != null) break;
            }
        }

        // Tier 2: contains thường
        if (match == null) {
            for (Category c : all) {
                if (c.getName() != null && c.getName().toLowerCase().contains(needle)) {
                    match = c;
                    break;
                }
            }
        }
        if (match == null) {
            for (Category c : all) {
                if (c.getName() != null && needle.contains(c.getName().toLowerCase())) {
                    match = c;
                    break;
                }
            }
        }
        if (match == null) return Collections.emptyList();

        if (match.getCategoryType() == CategoryType.BRAND) return Collections.emptyList();

        Category root = match;
        while (root.getCategoryType() != CategoryType.SUPER_CATEGORY && root.getParent() != null) {
            root = root.getParent();
        }

        Set<Long> ids = new HashSet<>();
        collectDescendants(root, all, ids);
        return new ArrayList<>(ids);
    }

    @Override
    public List<Long> expandByProductType(String productType) {
        if (productType == null || productType.isBlank()) return Collections.emptyList();
        String key = productType.trim().toUpperCase();
        List<String> kws = PRODUCT_TYPE_ALIASES.get(key);
        if (kws == null || kws.isEmpty()) return Collections.emptyList();

        List<Category> all = categoryRepository.findAll();
        Set<Long> acc = new HashSet<>();

        // Match STANDARD category by keyword, then expand descendants (in case có sub-category)
        for (String kw : kws) {
            for (Category c : all) {
                if (c.getCategoryType() == CategoryType.BRAND) continue;
                if (c.getName() != null && c.getName().toLowerCase().contains(kw)) {
                    collectDescendants(c, all, acc);
                }
            }
        }
        return new ArrayList<>(acc);
    }

    private void collectDescendants(Category root, List<Category> all, Set<Long> acc) {
        acc.add(root.getCategoryId());
        for (Category c : all) {
            if (c.getParent() != null && c.getParent().getCategoryId().equals(root.getCategoryId())) {
                if (c.getCategoryType() != CategoryType.BRAND) {
                    collectDescendants(c, all, acc);
                }
            }
        }
    }

    private CategoryResponseDTO mapToResponse(Category category) {
        return CategoryResponseDTO.builder()
                .id(category.getCategoryId())
                .name(category.getName())
                .description(category.getDescription())
                .parentName(category.getParent() != null ? category.getParent().getName() : null)
                .categoryType(category.getCategoryType())
                .build();
    }
}