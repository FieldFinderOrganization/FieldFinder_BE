package com.example.FieldFinder.mapper;

import java.util.*;

public class CategoryMapper {

    private static final Map<String, List<String>> ACTIVITY_CATEGORY_MAP = Map.ofEntries(
            Map.entry("football", List.of(
                    "Football Shoes",
                    "Football Clothing",
                    "Football Accessories",
                    "Socks"
            )),
            Map.entry("running", List.of(
                    "Running Shoes",
                    "Running Clothing",
                    "Running Accessories",
                    "Socks"
            )),
            Map.entry("basketball", List.of(
                    "Basketball Shoes",
                    "Basketball Clothing",
                    "Basketball Accessories",
                    "Socks"
            )),
            Map.entry("gym", List.of(
                    "Gym And Training",
                    "Clothing",
                    "Shoes",
                    "Accessories"
            )),
            Map.entry("tennis", List.of(
                    "Tennis Shoes",
                    "Tennis Clothing",
                    "Tennis Accessories",
                    "Socks"
            )),
            Map.entry("đá bóng", List.of(
                    "Football Shoes",
                    "Football Clothing",
                    "Football Accessories",
                    "Socks"
            )),
            Map.entry("chạy bộ", List.of(
                    "Running Shoes",
                    "Running Clothing",
                    "Running Accessories",
                    "Socks"
            )),
            Map.entry("bóng rổ", List.of(
                    "Basketball Shoes",
                    "Basketball Clothing",
                    "Basketball Accessories",
                    "Socks"
            ))
    );

    private static final Map<String, List<String>> AI_CATEGORY_ALIAS = Map.of(
            "áo đá bóng", List.of("Football Clothing"),
            "quần đá bóng", List.of("Football Clothing"),
            "giày đá bóng", List.of("Football Shoes"),
            "tất đá bóng", List.of("Socks"),

            "áo chạy bộ", List.of("Running Clothing"),
            "giày chạy bộ", List.of("Running Shoes"),

            "balo", List.of("Bags And Backpacks"),
            "găng tay", List.of("Gloves")
    );

    public static List<String> resolveCategories(
            String activity,
            List<String> aiCategories
    ) {
        Set<String> resolved = new LinkedHashSet<>();

        // Ưu tiên theo activity
        if (activity != null) {
            List<String> byActivity = ACTIVITY_CATEGORY_MAP.get(activity.toLowerCase());
            if (byActivity != null) {
                resolved.addAll(byActivity);
            }
        }

        // Map từ category AI
        if (aiCategories != null) {
            for (String c : aiCategories) {
                List<String> mapped = AI_CATEGORY_ALIAS.get(c.toLowerCase());
                if (mapped != null) {
                    resolved.addAll(mapped);
                }
            }
        }

        return new ArrayList<>(resolved);
    }
}