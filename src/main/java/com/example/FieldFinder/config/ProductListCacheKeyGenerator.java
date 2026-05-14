package com.example.FieldFinder.config;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stable cache keys for paginated product list (products_category).
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
        UUID userId = (UUID) params[4];

        String gpart = (genders == null || genders.isEmpty())
                ? ""
                : genders.stream().sorted().collect(Collectors.joining(","));

        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":"
                + (categoryId == null ? "n" : categoryId) + ":"
                + gpart + ":"
                + (brand == null ? "n" : brand) + ":"
                + (userId == null ? "anon" : userId);
    }
}
