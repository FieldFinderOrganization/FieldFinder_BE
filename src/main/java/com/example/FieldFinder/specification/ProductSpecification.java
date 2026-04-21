package com.example.FieldFinder.specification;

import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Product;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

public class ProductSpecification {

    public static Specification<Product> hasCategoryIds(Set<Long> categoryIds) {
        return (root, query, criteriaBuilder) -> {
            if (categoryIds == null || categoryIds.isEmpty()) {
                return null;
            }
            Join<Product, Category> categoryJoin = root.join("category");
            return categoryJoin.get("categoryId").in(categoryIds);
        };
    }

    public static Specification<Product> hasSex(Set<String> genders) {
        return (root, query, criteriaBuilder) -> {
            if (genders == null || genders.isEmpty()) {
                return null;
            }
            // In Product entity, sex is a String: 'Men', 'Women', 'Unisex'
            // If they select Men, we might want to include Unisex too?
            // The HomeState.dart logic: if targets.contains('Men') || targets.contains('Women') then targets.add('Unisex')
            return root.get("sex").in(genders);
        };
    }

    public static Specification<Product> hasBrand(String brand) {
        return (root, query, criteriaBuilder) -> {
            if (brand == null || brand.isEmpty()) {
                return null;
            }
            return criteriaBuilder.equal(criteriaBuilder.lower(root.get("brand")), brand.toLowerCase());
        };
    }
}