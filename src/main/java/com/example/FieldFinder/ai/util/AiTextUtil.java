package com.example.FieldFinder.ai.util;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Hàm format/dịch/nhận diện thuần (không state, không I/O) tách khỏi AIChat.
 * Dùng chung cho các intent handler của trợ lý AI.concur
 */
public final class AiTextUtil {

    private AiTextUtil() {}

    /** Chuẩn hóa list tag: trim + lowercase, bỏ null/rỗng, khử trùng lặp. Dùng chung image-search + enrichment. */
    public static List<String> sanitizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return new ArrayList<>();
        }
        return rawTags.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(tag -> tag.trim().toLowerCase())
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    public static String formatMoney(Double amount) {
        return String.format("%,.0f", amount);
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String productTypeLabel(String productType) {
        return switch (productType == null ? "" : productType.toUpperCase()) {
            case "SHOES" -> "giày";
            case "TOP" -> "áo";
            case "BOTTOM" -> "quần";
            case "DRESS" -> "váy";
            case "BAG" -> "balo/túi";
            case "HAT" -> "nón";
            case "SANDAL" -> "dép";
            default -> null;
        };
    }

    public static String translateCategory(String categoryKeyword) {
        if (categoryKeyword == null) return "";
        String key = categoryKeyword.toLowerCase().trim();
        if (key.contains("tennis accessories")) return "phụ kiện tennis";
        if (key.contains("running shoes")) return "giày chạy bộ";
        if (key.contains("football shoes")) return "giày đá bóng";
        if (key.contains("basketball shoes")) return "giày bóng rổ";
        if (key.contains("tennis shoes")) return "giày tennis";

        switch (key) {
            case "shoes": case "footwear": return "giày dép";
            case "clothing": return "quần áo";
            case "accessories": return "phụ kiện";
            case "hats and headwears": return "nón/mũ";
            case "socks": return "tất/vớ";
            case "gloves": return "găng tay";
            case "bags and backpacks": return "túi/balo";
            case "jackets and gilets": return "áo khoác";
            case "hoodies and sweatshirts": return "áo hoodie";
            case "pants and leggings": return "quần dài";
            case "shorts": return "quần đùi";
            case "tops and t-shirts": return "áo thun";
            case "gym and training": return "đồ tập gym";
            case "sandals and slides": return "dép/sandal";
            default: return categoryKeyword;
        }
    }

    /** Sân rẻ nhất / đắt nhất theo giá. null nếu list rỗng. */
    public static PitchResponseDTO findPitchByPrice(List<PitchResponseDTO> pitches, boolean findCheapest) {
        if (pitches.isEmpty()) return null;
        return findCheapest
                ? pitches.stream().min(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null)
                : pitches.stream().max(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null);
    }

    /**
     * TẤT CẢ sân cùng mức giá rẻ nhất / đắt nhất (xử lý tie — vd 3 sân cùng 40.000đ).
     * Trả list rỗng nếu input rỗng. Giữ nguyên thứ tự input cho các sân cùng giá.
     */
    public static List<PitchResponseDTO> findPitchesByPrice(List<PitchResponseDTO> pitches, boolean findCheapest) {
        if (pitches == null || pitches.isEmpty()) return java.util.Collections.emptyList();
        PitchResponseDTO extreme = findPitchByPrice(pitches, findCheapest);
        if (extreme == null || extreme.getPrice() == null) return java.util.Collections.emptyList();
        final java.math.BigDecimal target = extreme.getPrice();
        return pitches.stream()
                .filter(p -> p.getPrice() != null && p.getPrice().compareTo(target) == 0)
                .collect(java.util.stream.Collectors.toList());
    }

    /** Môi trường sân user nêu trong câu (INDOOR/OUTDOOR) hoặc null. */
    public static PitchEnvironment detectEnvironmentFromInput(String userInput) {
        if (userInput == null) return null;
        String input = userInput.toLowerCase();
        if (input.contains("ngoài trời") || input.contains("ngoai troi") || input.contains("outdoor") || input.contains("ngoài") || input.contains("bên ngoài")) return PitchEnvironment.OUTDOOR;
        if (input.contains("trong nhà") || input.contains("trong nha") || input.contains("indoor") || input.contains("trong") || input.contains("có mái") || input.contains("có mái che")) return PitchEnvironment.INDOOR;
        return null;
    }

    public static String formatEnvironment(PitchEnvironment env) {
        if (env == PitchEnvironment.INDOOR) return "trong nhà";
        else if (env == PitchEnvironment.OUTDOOR) return "ngoài trời";
        return "";
    }

    public static String formatPitchType(String type) {
        if (type.equals("FIVE_A_SIDE")) return "sân 5";
        if (type.equals("SEVEN_A_SIDE")) return "sân 7";
        if (type.equals("ELEVEN_A_SIDE")) return "sân 11";
        if (type.equals("ALL")) return "sân các loại (5, 7, 11)";
        return type;
    }

    /** Mô tả khoảng giá cho message AI (dưới/trên/từ-đến). */
    public static String buildPriceRangeMessage(Double minPrice, Double maxPrice) {
        if (maxPrice >= Double.MAX_VALUE - 1) {
            return "trên " + formatMoney(minPrice) + " VNĐ";
        } else if (minPrice == 0 || minPrice < 1) {
            return "dưới " + formatMoney(maxPrice) + " VNĐ";
        } else {
            return "từ " + formatMoney(minPrice) + " đến " + formatMoney(maxPrice) + " VNĐ";
        }
    }

    /** Mô tả tiêu chí user nêu cho message: "giày Nike nam màu đen". */
    public static String buildCriteriaDesc(String productType, String brand, String genderVN, String color) {
        String typeLabel = switch (productType == null ? "" : productType) {
            case "SHOES" -> "giày";
            case "TOP" -> "áo";
            case "BOTTOM" -> "quần";
            case "DRESS" -> "váy";
            case "BAG" -> "balo/túi";
            case "HAT" -> "nón";
            case "SANDAL" -> "dép";
            default -> "sản phẩm";
        };
        StringBuilder sb = new StringBuilder(typeLabel);
        if (brand != null) sb.append(' ').append(brand);
        if (genderVN != null) sb.append(' ').append(genderVN);
        if (color != null) sb.append(" màu ").append(color);
        return sb.toString();
    }

    /** Giá thực tế của sản phẩm: salePrice nếu đang giảm, ngược lại price gốc. */
    public static double effectivePrice(ProductResponseDTO p) {
        if (p.getSalePercent() != null && p.getSalePercent() > 0 && p.getSalePrice() != null) {
            return p.getSalePrice();
        }
        return p.getPrice() != null ? p.getPrice() : 0.0;
    }

    public static boolean isGreeting(String s) {
        String t = s.toLowerCase().trim();
        return t.matches("^(hi|hey|hello|hola|halo|alo|yo|chào|xin chào|good morning|good evening|good afternoon)[\\s!?.]*$")
                || t.matches(".*\\b(xin chào|chào bạn|chào shop|hello|good morning|good evening|good afternoon)\\b.*");
    }

    /** User hỏi rõ "rẻ nhất"/"mắc nhất" → trả đúng 1 sản phẩm cực trị. */
    public static boolean isExplicitPriceExtremeQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String lower = userInput.toLowerCase();
        return lower.contains("rẻ nhất") || lower.contains("re nhat")
                || lower.contains("mắc nhất") || lower.contains("mac nhat")
                || lower.contains("đắt nhất") || lower.contains("dat nhat")
                || lower.contains("giá thấp nhất") || lower.contains("gia thap nhat")
                || lower.contains("giá cao nhất") || lower.contains("gia cao nhat")
                || lower.contains("cheapest") || lower.contains("most expensive");
    }

    /**
     * User muốn danh sách sản phẩm giá mềm (vd "mắc quá cho mấy đôi rẻ"),
     * không phải 1 sản phẩm rẻ nhất tuyệt đối.
     */
    public static boolean isAffordableListQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        if (isExplicitPriceExtremeQuery(userInput)) return false;
        String lower = userInput.toLowerCase();
        return lower.contains("rẻ") || lower.contains("re ")
                || lower.contains("giá rẻ") || lower.contains("gia re")
                || lower.contains("rẻ hơn") || lower.contains("re hon")
                || lower.contains("mắc quá") || lower.contains("mac qua")
                || lower.contains("đắt quá") || lower.contains("dat qua")
                || lower.contains("tiết kiệm") || lower.contains("tiet kiem")
                || lower.contains("budget") || lower.contains("affordable");
    }
}
