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

    /** Categories that activity maps to: e.g. ["Tennis Shoes", "Tennis Clothing", "Tennis Accessories"] */
    private final Set<String> activityCats;

    /**
     * When true and productType is set, tier 3 (wrong type + activity) and tier 4 (best-seller fill) are skipped.
     */
    @Builder.Default
    private final boolean strictProductType = false;

    // ============== Composite weights (sum ~1.0) ==============

    @Builder.Default
    private final double wType = 0.30;

    @Builder.Default
    private final double wActivity = 0.20;

    @Builder.Default
    private final double wBrand = 0.15;

    @Builder.Default
    private final double wGender = 0.10;

    @Builder.Default
    private final double wMl = 0.15;

    @Builder.Default
    private final double wText = 0.10;
}
