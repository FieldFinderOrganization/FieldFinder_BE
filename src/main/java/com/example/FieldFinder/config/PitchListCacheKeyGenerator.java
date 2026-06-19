package com.example.FieldFinder.config;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Stable cache keys for paginated pitch list (pitches_list).
 */
@Component("pitchListCacheKeyGenerator")
public class PitchListCacheKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Pageable pageable = (Pageable) params[0];
        String district = (String) params[1];
        String type = (String) params[2];
        String name = (String) params[3];

        // PHẢI gồm sort: nếu không, đổi sort giá (asc/desc/none) cho ra cùng key
        // → cache trả lại list theo thứ tự đã cache đầu tiên → "lọc/sắp xếp giá bị sai".
        String sort = pageable.getSort().isSorted()
                ? pageable.getSort().toString()
                : "unsorted";

        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":"
                + (district == null ? "n" : district) + ":"
                + (type == null ? "n" : type) + ":"
                + (name == null ? "n" : name) + ":"
                + sort;
    }
}
