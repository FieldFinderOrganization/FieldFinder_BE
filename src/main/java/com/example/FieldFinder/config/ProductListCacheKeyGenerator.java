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
        String brand = (String) params[3];
        String name = params.length > 4 ? (String) params[4] : null;

        String gpart = (genders == null || genders.isEmpty())
                ? ""
                : genders.stream().sorted().collect(Collectors.joining(","));

        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":"
                + (categoryId == null ? "n" : categoryId) + ":"
                + gpart + ":"
                + (brand == null ? "n" : brand) + ":"
                + (name == null || name.isBlank() ? "n" : name.toLowerCase().trim());
    }
}
