package com.example.FieldFinder.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bộ màu chuẩn (canonical) cho tìm kiếm theo màu — image + text.
 *
 * <p>Mỗi "surface form" (đen/black/than/charcoal…) ánh xạ về ĐÚNG MỘT màu canonical (tiếng Việt).
 * Đây là bản thay thế có kiểm soát cho {@code AIChat.expandColorTags} cũ — vốn nới quá tay
 * (grey → đen, navy → xanh, hồng → cam+đỏ+tím) khiến hàng sai màu lọt vào kết quả. Ở đây:
 * <ul>
 *   <li>grey/ghi/gray → <b>xám</b> (màu riêng, KHÔNG còn gộp vào đen)</li>
 *   <li>navy/sky/biển → <b>xanh dương</b> (không kéo theo xanh lá)</li>
 *   <li>mỗi surface form chỉ thuộc 1 canonical → không lan chéo màu</li>
 * </ul>
 *
 * Dùng để: chuẩn hóa màu AI sinh (dominantColor), trích màu từ câu truy vấn, và so khớp màu.
 */
public final class ColorVocab {

    private ColorVocab() {}

    /** canonical (VN) -> mọi surface form (gồm cả chính nó). Thứ tự khai báo = thứ tự ưu tiên. */
    private static final Map<String, List<String>> CANON_TO_SURFACES = new LinkedHashMap<>();
    /** surface form (lowercase) -> canonical. */
    private static final Map<String, String> SURFACE_TO_CANON = new LinkedHashMap<>();
    /** surface forms sắp xếp dài → ngắn để match "xanh dương" trước "xanh". */
    private static final List<String> SURFACES_BY_LEN_DESC;

    private static void put(String canon, String... surfaces) {
        List<String> all = new ArrayList<>();
        all.add(canon);
        for (String s : surfaces) all.add(s);
        CANON_TO_SURFACES.put(canon, all);
        for (String s : all) SURFACE_TO_CANON.put(s.toLowerCase(), canon);
    }

    static {
        // Lưu ý: "xanh lá"/"xanh dương" phải đứng trước "xanh" (xử lý longest-first khi match).
        put("đen", "black", "than", "charcoal", "ebony");
        put("trắng", "white", "kem", "cream", "beige", "sữa", "ivory");
        put("xám", "gray", "grey", "ghi", "bạc", "silver");
        put("đỏ", "red", "crimson", "đỏ tươi", "đỏ đô", "burgundy");
        put("cam", "orange", "coral");
        put("vàng", "yellow", "gold", "vàng gold");
        put("hồng", "pink", "mận", "hồng phấn");
        put("tím", "purple", "violet");
        put("nâu", "brown", "tan", "cà phê", "cafe", "coffee");
        put("xanh lá", "green", "olive", "rêu", "xanh lá cây", "xanh rêu", "xanh lục");
        put("xanh dương", "blue", "navy", "chàm", "sky", "biển", "xanh biển",
                "xanh nước biển", "xanh navy", "xanh");

        List<String> surfaces = new ArrayList<>(SURFACE_TO_CANON.keySet());
        surfaces.sort((a, b) -> Integer.compare(b.length(), a.length()));
        SURFACES_BY_LEN_DESC = surfaces;
    }

    /**
     * Chuẩn hóa một chuỗi màu (vd "Black", "than", "Trắng phối Cam") về canonical VN.
     * Với mô tả nhiều màu → trả màu canonical ĐẦU TIÊN tìm thấy (màu chủ đạo do AI đặt trước).
     * Trả null nếu không nhận ra màu nào.
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.toLowerCase().trim();
        // Khớp nguyên chuỗi trước (vd "đen", "black").
        String direct = SURFACE_TO_CANON.get(s);
        if (direct != null) return direct;
        // Nếu là mô tả dài → quét token màu đầu tiên (longest-first).
        return detectInText(s);
    }

    /**
     * Quét text, trả màu canonical ĐẦU TIÊN xuất hiện (theo vị trí trong câu, ưu tiên cụm dài).
     * Match theo nguyên-token (ranh giới chữ/số Unicode) để "đen" không dính trong "đèn".
     */
    public static String detectInText(String text) {
        if (text == null || text.isBlank()) return null;
        String hay = text.toLowerCase();
        int bestPos = Integer.MAX_VALUE;
        int bestLen = -1;
        String best = null;
        for (String surface : SURFACES_BY_LEN_DESC) {
            int pos = wholeWordIndex(hay, surface);
            if (pos < 0) continue;
            // Ưu tiên: xuất hiện sớm hơn; nếu cùng vị trí thì cụm dài hơn thắng.
            if (pos < bestPos || (pos == bestPos && surface.length() > bestLen)) {
                bestPos = pos;
                bestLen = surface.length();
                best = SURFACE_TO_CANON.get(surface);
            }
        }
        return best;
    }

    /** Index của lần khớp nguyên-token đầu tiên, -1 nếu không có. */
    private static int wholeWordIndex(String haystack, String needle) {
        Pattern p = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])");
        var m = p.matcher(haystack);
        return m.find() ? m.start() : -1;
    }

    /** True nếu text chứa (nguyên-token) màu canonical đã cho hoặc bất kỳ surface form nào của nó. */
    public static boolean textMatchesColor(String text, String canonicalColor) {
        if (text == null || canonicalColor == null) return false;
        List<String> surfaces = CANON_TO_SURFACES.get(canonicalColor);
        if (surfaces == null) return false;
        String hay = text.toLowerCase();
        for (String s : surfaces) {
            if (wholeWordIndex(hay, s) >= 0) return true;
        }
        return false;
    }

    /** True nếu chuỗi raw là (chuẩn hóa về) cùng màu canonical với canonicalColor. */
    public static boolean sameColor(String rawColor, String canonicalColor) {
        if (canonicalColor == null) return false;
        String c = canonical(rawColor);
        return c != null && c.equals(canonicalColor);
    }

    /**
     * Chuẩn hóa một tập màu raw → LinkedHashSet canonical (giữ thứ tự xuất hiện, bỏ trùng/không nhận ra).
     * Dùng cho cột `colors` (sp đa màu). {@code firstPriority} (vd dominantColor) nếu có sẽ được
     * đưa lên đầu. {@code cap} = số màu tối đa giữ lại (≤0 = không giới hạn).
     */
    public static java.util.LinkedHashSet<String> canonicalSet(
            Iterable<String> raws, String firstPriority, int cap) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String first = canonical(firstPriority);
        if (first != null) out.add(first);
        if (raws != null) {
            for (String r : raws) {
                String c = canonical(r);
                if (c != null) out.add(c);
            }
        }
        if (cap > 0 && out.size() > cap) {
            java.util.LinkedHashSet<String> capped = new java.util.LinkedHashSet<>();
            int i = 0;
            for (String c : out) {
                if (i++ >= cap) break;
                capped.add(c);
            }
            return capped;
        }
        return out;
    }
}
