package com.example.FieldFinder.ai.match;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.util.ColorVocab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper khớp sản phẩm dùng chung cho các intent của trợ lý AI (image-search, product-query,
 * recommend): nhận diện brand trong câu hỏi, chuẩn hóa productType, lọc đúng loại. Thuần (static),
 * dependency (catalog, CategoryService) truyền vào — tách khỏi AIChat để handler dùng chung.
 */
public final class AiProductMatch {

    private AiProductMatch() {}

    /** Khớp token nguyên từ (word-boundary unicode), không khớp chuỗi con (spumante ≠ puma). */
    public static boolean containsQueryToken(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return false;
        Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                + Pattern.quote(needle)
                + "(?![\\p{L}\\p{N}])");
        return pattern.matcher(haystack).find();
    }

    /** Chuẩn hóa productType AI trả về → bộ chuẩn nội bộ; null nếu rỗng/không hợp lệ. */
    public static String normalizeAiProductType(Object raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty() || "NULL".equals(t)) {
            return null;
        }
        return switch (t) {
            case "SHOES", "SHOE", "FOOTWEAR" -> "SHOES";
            case "TOP", "TOPS", "SHIRT", "JERSEY" -> "TOP";
            case "BOTTOM", "BOTTOMS", "PANTS", "SHORTS" -> "BOTTOM";
            case "SANDAL", "SANDALS" -> "SANDAL";
            case "DRESS" -> "DRESS";
            case "BAG", "BAGS", "BACKPACK" -> "BAG";
            case "HAT", "CAP" -> "HAT";
            case "OTHER", "ACCESSORY", "ACCESSORIES" -> "OTHER";
            default -> t.matches("SHOES|TOP|BOTTOM|SANDAL|DRESS|BAG|HAT|OTHER") ? t : null;
        };
    }

    public record StrictTypeFilterResult(List<ProductResponseDTO> products, List<Double> scores) {}

    /** Giữ lại sản phẩm đúng productType (giữ score song song). Null-safe → trả rỗng. */
    public static StrictTypeFilterResult strictTypeFilter(
            List<ProductResponseDTO> products,
            List<Double> scores,
            String productType,
            CategoryService categoryService) {
        List<ProductResponseDTO> filteredProducts = new ArrayList<>();
        List<Double> filteredScores = new ArrayList<>();
        if (products == null || scores == null || productType == null || categoryService == null) {
            return new StrictTypeFilterResult(filteredProducts, filteredScores);
        }
        for (int i = 0; i < products.size(); i++) {
            ProductResponseDTO product = products.get(i);
            if (categoryService.productMatchesType(product, productType)) {
                filteredProducts.add(product);
                filteredScores.add(i < scores.size() ? scores.get(i) : 0.0);
            }
        }
        return new StrictTypeFilterResult(filteredProducts, filteredScores);
    }

    /** Màu user nêu (Gemini parse ưu tiên) → canonical; fallback dò trong userInput. */
    public static String detectQueryColor(Object parsedColor, String userInput) {
        String fromGemini = ColorVocab.canonical(parsedColor != null ? parsedColor.toString() : null);
        if (fromGemini != null) return fromGemini;
        return ColorVocab.detectInText(userInput);
    }

    /**
     * Giới tính user nêu thẳng → "MEN"/"WOMEN"/null. Ưu tiên tags Gemini; fallback token trong userInput.
     * Check nữ trước (tránh "nữ" chứa "nu" gây nhầm).
     */
    public static String detectQueryGender(List<String> tags, String userInput) {
        List<String> tagsLower = tags == null ? Collections.emptyList()
                : tags.stream().map(t -> t == null ? "" : t.toLowerCase()).toList();
        if (tagsLower.contains("nữ") || tagsLower.contains("nu")
                || tagsLower.contains("women") || tagsLower.contains("woman")) {
            return "WOMEN";
        }
        if (tagsLower.contains("nam") || tagsLower.contains("men") || tagsLower.contains("man")) {
            return "MEN";
        }
        if (containsQueryToken(userInput, "nữ") || containsQueryToken(userInput, "women")
                || containsQueryToken(userInput, "woman")) {
            return "WOMEN";
        }
        if (containsQueryToken(userInput, "nam") || containsQueryToken(userInput, "men")
                || containsQueryToken(userInput, "man")) {
            return "MEN";
        }
        return null;
    }

    /** "size 39" / "Size 39,5" / "cỡ 40" / "sz XL" — group 1 = giá trị size. */
    private static final Pattern QUERY_SIZE_PATTERN = Pattern.compile(
            "(?iu)(?:size|sz|cỡ|số)\\s*:?\\s*(\\d{1,3}(?:[.,]5)?|[2-5]xl|x{1,3}l|xs|[sml])(?![\\p{L}\\p{N}])");

    /** Size user nêu thẳng. Ưu tiên field size Gemini; fallback regex userInput. null nếu không nêu. */
    public static String detectQuerySize(Object parsedSize, String userInput) {
        if (parsedSize != null) {
            String s = parsedSize.toString().trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        if (userInput == null) return null;
        Matcher m = QUERY_SIZE_PATTERN.matcher(userInput);
        if (m.find()) return m.group(1).replace(',', '.').toUpperCase();
        return null;
    }

    /** Dò brand dài nhất khớp trong (productName + userInput + tags) so với brand có trong catalog. */
    public static String detectQueryBrand(List<ProductResponseDTO> catalog, String productName,
                                          List<String> tags, String userInput) {
        StringBuilder hayB = new StringBuilder();
        if (productName != null) hayB.append(' ').append(productName);
        if (userInput != null) hayB.append(' ').append(userInput);
        if (tags != null) {
            for (String t : tags) {
                if (t != null) hayB.append(' ').append(t);
            }
        }
        String hay = hayB.toString().toLowerCase();
        if (hay.isBlank()) return null;

        String best = null;
        int bestLen = 0;
        Set<String> seen = new HashSet<>();
        if (catalog != null) {
            for (ProductResponseDTO p : catalog) {
                String brand = p.getBrand();
                if (brand == null || brand.isBlank()) continue;
                String b = brand.toLowerCase();
                if (!seen.add(b)) continue;
                if (containsQueryToken(hay, b) && b.length() > bestLen) {
                    best = brand;
                    bestLen = b.length();
                }
            }
        }
        return best;
    }
}
