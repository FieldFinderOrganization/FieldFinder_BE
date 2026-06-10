package com.example.FieldFinder.ai.ranking;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Set;

/**
 * Context bag for composite ranking — product type, activity, gender, brand prefs + weights.
 *
 * Used by CompositeRanker.rank() to score and tier candidates from ML retrieve.
 */
@Builder
@Getter
public class RankingContext {

    /** Detected product type from query: SHOES / BAG / TOP / BOTTOM / DRESS / HAT / SANDAL / OTHER */
    private final String productType;

    /** Activity from Gemini: tennis / basketball / football / running / gym / ... */
    private final String activity;

    /** Gender pref from tags: MEN / WOMEN / null (no pref) */
    private final String genderPref;

    /** User's top brands from MongoDB history (top N most-viewed) */
    private final List<String> topBrands;

    /** Brand explicitly named in the query (e.g. "adidas" from "balo adidas"); null if none. */
    private final String queryBrand;

    /** Color explicitly named in the query, canonical (e.g. "đen" from "giày nike màu đen"); null if none. */
    private final String queryColor;

    /** Gender explicitly named in the query ("nam"/"nữ" → MEN/WOMEN); null if none. */
    private final String queryGender;

    /** Size explicitly named in the query (e.g. "39", "XL" from "giày nike size 39"); null if none. */
    private final String querySize;

    /** Categories that activity maps to: e.g. ["Tennis Shoes", "Tennis Clothing", "Tennis Accessories"] */
    private final Set<String> activityCats;

    /**
     * When true and productType is set, tier 3 (wrong type + activity) and tier 4 (best-seller fill) are skipped.
     */
    @Builder.Default
    private final boolean strictProductType = false;

    /**
     * Số kết quả tối đa tier 1 trả về. Nâng lên (Integer.MAX_VALUE) khi query nêu size để
     * tiered grouping phía sau thấy đủ ứng viên (vd "mẫu brand khác còn size 39" thường rank
     * sau toàn bộ sp đúng brand → bị cắt nếu chỉ lấy 10). Hiển thị trim lại sau grouping.
     */
    @Builder.Default
    private final int targetSize = 10;

    // ============== Composite weights (sum ~1.0) ==============

    @Builder.Default
    private final double wType = 0.25;

    @Builder.Default
    private final double wActivity = 0.18;

    @Builder.Default
    private final double wBrand = 0.13;

    /** Trọng số khớp màu chủ đạo (chỉ tác dụng khi queryColor != null). */
    @Builder.Default
    private final double wColor = 0.15;

    @Builder.Default
    private final double wGender = 0.09;

    @Builder.Default
    private final double wMl = 0.12;

    @Builder.Default
    private final double wText = 0.08;
}
