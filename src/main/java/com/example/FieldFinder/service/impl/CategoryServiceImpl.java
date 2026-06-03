package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.Enum.CategoryType;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
            "SHOES",   List.of("giày", "shoe", "sneaker", "football boot", "cleats"),
            "SANDAL",  List.of("dép", "sandal", "slipper"),
            "TOP",     List.of("áo", "shirt", "jersey", "hoodie", "jacket", "polo", "sơ mi", "tee"),
            "BOTTOM",  List.of("quần", "shorts", "pants", "trousers", "jeans", "jogger", "quần short"),
            "DRESS",   List.of("váy", "đầm", "dress", "skirt"),
            "BAG",     List.of("balo", "ba lô", "túi", "bag", "backpack"),
            "HAT",     List.of("nón", "mũ", "beanie", "hat", "cap"),
            "OTHER",   List.of("phụ kiện", "accessory", "kính", "vớ", "găng")
    );

    /** categoryKeyword DB name → productType khi không mơ hồ. */
    private static final Map<String, String> CATEGORY_KEYWORD_TO_TYPE = Map.ofEntries(
            Map.entry("tops and t-shirts", "TOP"),
            Map.entry("hoodies and sweatshirts", "TOP"),
            Map.entry("jackets and gilets", "TOP"),
            Map.entry("pants and leggings", "BOTTOM"),
            Map.entry("shorts", "BOTTOM"),
            Map.entry("football shoes", "SHOES"),
            Map.entry("tennis shoes", "SHOES"),
            Map.entry("basketball shoes", "SHOES"),
            Map.entry("running shoes", "SHOES"),
            Map.entry("shoes", "SHOES"),
            Map.entry("lifestyle", "SHOES"),
            Map.entry("sandals and slides", "SANDAL"),
            Map.entry("bags and backpacks", "BAG"),
            Map.entry("hats and headwears", "HAT"),
            Map.entry("socks", "OTHER"),
            Map.entry("running accessories", "OTHER"),
            Map.entry("tennis accessories", "OTHER"),
            Map.entry("basketball accessories", "OTHER"),
            Map.entry("football accessories", "OTHER")
    );

    /** Danh mục rộng — không dùng substring để suy productType (tránh nhầm áo ↔ quần). */
    private static final Set<String> AMBIGUOUS_CATEGORY_KEYWORDS = Set.of(
            "football clothing", "tennis clothing", "basketball clothing",
            "running clothing", "clothing", "gym and training"
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

    @Override
    public String detectProductTypeFromQuery(String query, List<String> tags, String categoryKeyword) {
        Set<String> intentHay = new HashSet<>();
        if (query != null && !query.isBlank()) {
            intentHay.add(query.toLowerCase(Locale.ROOT));
        }
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) {
                    intentHay.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }

        // 1) Ưu tiên ý định user (câu hỏi + tags) — "áo bóng đá" → TOP, không bị categoryKeyword lệch
        String fromIntent = scoreProductType(intentHay);
        if (fromIntent != null) {
            return fromIntent;
        }

        // 2) categoryKeyword cụ thể (Tops And T-Shirts, Football Shoes, ...)
        if (categoryKeyword != null && !categoryKeyword.isBlank()) {
            String fromCategory = mapCategoryKeywordToType(categoryKeyword);
            if (fromCategory != null) {
                return fromCategory;
            }
        }

        // 3) Fallback: categoryKeyword không mơ hồ (không phải "Football Clothing" chung chung)
        if (categoryKeyword != null && !categoryKeyword.isBlank()) {
            String ck = categoryKeyword.toLowerCase(Locale.ROOT).trim();
            if (!AMBIGUOUS_CATEGORY_KEYWORDS.contains(ck)) {
                Set<String> hay = new HashSet<>(intentHay);
                hay.add(ck);
                return scoreProductType(hay);
            }
        }

        return null;
    }

    private static String mapCategoryKeywordToType(String categoryKeyword) {
        if (categoryKeyword == null || categoryKeyword.isBlank()) {
            return null;
        }
        return CATEGORY_KEYWORD_TO_TYPE.get(categoryKeyword.toLowerCase(Locale.ROOT).trim());
    }

    private static String scoreProductType(Set<String> hay) {
        if (hay.isEmpty()) {
            return null;
        }
        String bestType = null;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : PRODUCT_TYPE_ALIASES.entrySet()) {
            int hits = 0;
            for (String kw : entry.getValue()) {
                for (String h : hay) {
                    if (containsProductTypeKeyword(h, kw)) {
                        hits++;
                        break;
                    }
                }
            }
            if (hits > bestScore) {
                bestScore = hits;
                bestType = entry.getKey();
            }
        }
        return bestType;
    }

    /**
     * Khớp từ/cụm từ — tránh substring sai (vd: "short" trong "clothing", "boot" trong "football").
     */
    private static boolean containsProductTypeKeyword(String haystack, String keyword) {
        if (haystack == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        String k = keyword.toLowerCase(Locale.ROOT).trim();
        if (k.contains(" ")) {
            return h.contains(k);
        }
        if (k.length() >= 6) {
            return h.contains(k);
        }
        Pattern p = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p.matcher(h).find();
    }

    @Override
    public boolean productMatchesType(ProductResponseDTO product, String productType) {
        if (product == null || productType == null) return false;
        List<String> kws = PRODUCT_TYPE_ALIASES.get(productType.toUpperCase());
        if (kws == null || kws.isEmpty()) return false;

        String reqType = productType.toUpperCase();
        String name = product.getName() != null ? product.getName().toLowerCase() : "";
        String catName = product.getCategoryName() != null ? product.getCategoryName().toLowerCase() : "";

        // 0a) NAME states the requested type directly (word-boundary keyword) → accept even if
        //     the leaf category is mis-filed. E.g. a "...Skirt" product filed under "Shorts" is
        //     asked as DRESS: trust the name. Additive — only ever turns the REQUESTED type ON,
        //     so it can't reintroduce cross-type tag leak (the reject below is unchanged).
        for (String kw : kws) {
            if (containsProductTypeKeyword(name, kw)) return true;
        }

        // 0b) AUTHORITATIVE: resolve the product's own type from its (clean) leaf category
        //    name. Category taxonomy is reliable (e.g. "Pants And Leggings" → BOTTOM,
        //    "Basketball Shoes" → SHOES), unlike image/outfit-level tags. When it resolves
        //    to a concrete type we trust it fully and ignore name/tag noise.
        String resolvedType = mapCategoryKeywordToType(catName);
        if (resolvedType != null) {
            return resolvedType.equals(reqType);
        }

        String strong = (name + " " + catName).trim();

        // Strong signal: requested type keyword in product name or category → accept.
        for (String kw : kws) {
            if (!strong.isEmpty() && strong.contains(kw)) return true;
        }

        // Cross-type exclusion: if name/category strongly matches a DIFFERENT, mutually
        // exclusive type (e.g. "pants" → BOTTOM when SHOES was asked), reject.
        for (Map.Entry<String, List<String>> e : PRODUCT_TYPE_ALIASES.entrySet()) {
            if (e.getKey().equals(reqType) || "OTHER".equals(e.getKey())) continue;
            for (String kw : e.getValue()) {
                if (!strong.isEmpty() && strong.contains(kw)) return false;
            }
        }

        // NOTE: image/outfit-level `tags` deliberately NOT used as a fallback — they leak
        // cross-type items (e.g. "Running Accessories" matching TOP via an "áo" tag). In-category
        // recall is now guaranteed upstream by the DB category-augment step in AIChat, so a
        // product whose name AND clean category give no type signal is correctly treated as a
        // non-match here.
        return false;
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