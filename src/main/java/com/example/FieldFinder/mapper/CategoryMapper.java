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

    public static List<String> resolveCategories(String activity, List<String> aiCategories) {
        return resolveCategories(activity, aiCategories, null);
    }

    /**
     * Resolve category names for ranking. When Gemini returns a specific categoryKeyword
     * (e.g. "Football Shoes"), do not broaden to all activity categories (which would include pants).
     */
    public static List<String> resolveCategories(
            String activity,
            List<String> aiCategories,
            String categoryKeyword
    ) {
        Set<String> resolved = new LinkedHashSet<>();

        if (categoryKeyword != null && !categoryKeyword.isBlank()) {
            resolved.add(categoryKeyword.trim());
        }

        if (aiCategories != null) {
            for (String c : aiCategories) {
                if (c == null || c.isBlank()) continue;
                String trimmed = c.trim();
                resolved.add(trimmed);
                List<String> mapped = AI_CATEGORY_ALIAS.get(trimmed.toLowerCase());
                if (mapped != null) {
                    resolved.addAll(mapped);
                }
            }
        }

        if (!resolved.isEmpty() && hasSpecificProductCategory(resolved)) {
            return new ArrayList<>(resolved);
        }

        resolved.clear();
        if (activity != null) {
            List<String> byActivity = ACTIVITY_CATEGORY_MAP.get(activity.toLowerCase());
            if (byActivity != null) {
                resolved.addAll(byActivity);
            }
        }

        if (aiCategories != null) {
            for (String c : aiCategories) {
                if (c == null || c.isBlank()) continue;
                List<String> mapped = AI_CATEGORY_ALIAS.get(c.toLowerCase());
                if (mapped != null) {
                    resolved.addAll(mapped);
                } else {
                    resolved.add(c.trim());
                }
            }
        }

        return new ArrayList<>(resolved);
    }

    /** True when categories are specific DB names, not generic "Shoes"/"Clothing". */
    private static boolean hasSpecificProductCategory(Set<String> categories) {
        for (String c : categories) {
            if (c == null || c.isBlank()) continue;
            String lower = c.toLowerCase().trim();
            if (lower.equals("shoes") || lower.equals("clothing") || lower.equals("accessories")
                    || lower.equals("footwear") || lower.equals("apparel")) {
                continue;
            }
            if (c.contains(" ") || lower.endsWith(" shoes") || lower.endsWith(" clothing")
                    || lower.contains("shorts") || lower.contains("socks")
                    || lower.contains("backpack") || lower.contains("jacket")
                    || lower.contains("hoodie") || lower.contains("pants")
                    || lower.contains("sandals")) {
                return true;
            }
        }
        return false;
    }
}