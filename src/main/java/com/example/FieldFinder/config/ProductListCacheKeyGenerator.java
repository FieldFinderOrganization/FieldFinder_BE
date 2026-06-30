package com.example.FieldFinder.config;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stable cache keys for paginated product list (products_category).
 * Key KHÔNG kèm userId — base cache shared giữa users; overlay per-user
 * apply post-cache trong service layer.
 * Params: (pageable, categoryId, genders, brand, name)
 */
@Component("productListCacheKeyGenerator")
public class ProductListCacheKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Pageable pageable = (Pageable) params[0];
        Long categoryId = (Long) params[1];
        @SuppressWarnings("unchecked")
        Set<String> genders = (Set<String>) params[2];
        @SuppressWarnings("unchecked")
        Set<String> brands = (Set<String>) params[3];
        String name = params.length > 4 ? (String) params[4] : null;

        String gpart = (genders == null || genders.isEmpty())
                ? ""
                : genders.stream().sorted().collect(Collectors.joining(","));

        // Multi-brand: sort để (A,B) và (B,A) trùng key.
        String bpart = (brands == null || brands.isEmpty())
                ? "n"
                : brands.stream().sorted().collect(Collectors.joining(","));

        // Sort phải vào key: cùng (page,category,brand,...) nhưng khác sort = page khác.
        // Bỏ sort -> mọi lựa chọn sắp xếp giá đều hit chung 1 cache entry -> sort vô hiệu.
        String spart = pageable.getSort().isSorted()
                ? pageable.getSort().stream()
                        .map(o -> o.getProperty() + "," + o.getDirection())
                        .collect(Collectors.joining(";"))
                : "u";

        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":"
                + (categoryId == null ? "n" : categoryId) + ":"
                + gpart + ":"
                + bpart + ":"
                + (name == null || name.isBlank() ? "n" : name.toLowerCase().trim()) + ":"
                + spart;
    }
}
