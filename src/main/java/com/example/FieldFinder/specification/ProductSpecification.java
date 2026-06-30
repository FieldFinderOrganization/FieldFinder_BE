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

    /** Lọc theo NHIỀU thương hiệu (OR). Rỗng/null = không lọc. */
    public static Specification<Product> hasBrands(java.util.Collection<String> brands) {
        return (root, query, criteriaBuilder) -> {
            if (brands == null || brands.isEmpty()) {
                return null;
            }
            java.util.List<String> lowered = brands.stream()
                    .filter(b -> b != null && !b.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
            if (lowered.isEmpty()) {
                return null;
            }
            return criteriaBuilder.lower(root.get("brand")).in(lowered);
        };
    }

    /**
     * Tìm theo từ khóa tên: khớp LIKE (không phân biệt hoa thường) trên tên
     * hoặc thương hiệu sản phẩm. Dùng cho thanh tìm kiếm tab Shop (search toàn DB).
     */
    public static Specification<Product> hasNameLike(String name) {
        return (root, query, criteriaBuilder) -> {
            if (name == null || name.isBlank()) {
                return null;
            }
            String pattern = "%" + name.toLowerCase().trim() + "%";
            return criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("brand")), pattern));
        };
    }
}