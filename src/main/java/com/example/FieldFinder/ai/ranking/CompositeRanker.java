package com.example.FieldFinder.ai.ranking;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Composite scoring + soft tier degrade ranker.
 *
 * Tier layout (target 10 items):
 *  Tier 1 (3): strict type + activity + brand + gender, degrade if insufficient
 *  Tier 2 (2): type match, drop activity OR gender
 *  Tier 3 (2): NOT type, but activity match
 *  Tier 4 (3): best-seller fill (remaining candidates by totalSold)
 *
 * Each candidate scored with composite formula:
 *   score = w_type*typeMatch + w_activity*actMatch + w_brand*brandPref
 *         + w_gender*genderScore + w_ml*mlNorm + w_text*0.5
 */
@Component
@RequiredArgsConstructor
public class CompositeRanker {

    private final CategoryService categoryService;

    /** Target result count returned to the AI chat. */
    private static final int TARGET_SIZE = 10;

    public List<Map.Entry<ProductResponseDTO, Double>> rank(
            List<ProductResponseDTO> candidates,
            List<Double> mlScores,
            RankingContext ctx) {

        if (candidates == null || candidates.isEmpty()) return List.of();
        if (mlScores == null || mlScores.size() != candidates.size()) {
            // Defensive: fill with 0.5 if mismatch
            mlScores = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) mlScores.add(0.5);
        }

        // Normalize ML scores → [0, 1]
        double maxMl = mlScores.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double minMl = mlScores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double mlRange = Math.max(maxMl - minMl, 1e-9);

        // Score each candidate
        List<ScoredProduct> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ProductResponseDTO p = candidates.get(i);
            double mlNorm = (mlScores.get(i) - minMl) / mlRange;
            boolean typeMatch = categoryService.productMatchesType(p, ctx.getProductType());
            boolean activityMatch = activityMatches(p, ctx.getActivityCats());
            boolean brandMatch = brandMatches(p, ctx.getTopBrands());
            boolean genderMatch = genderMatches(p, ctx.getGenderPref());

            double composite = computeComposite(typeMatch, activityMatch, brandMatch,
                    p, mlNorm, ctx);
            scored.add(new ScoredProduct(p, composite, typeMatch, activityMatch, brandMatch, genderMatch));
        }

        // ============== TIER 1: strict match all 4 dims with degrade chain ==============
        List<ScoredProduct> tier1 = filter(scored, sp -> sp.type && sp.activity && sp.brand && sp.gender);
        String tier1Source = "strict";
        if (tier1.size() < 3) {
            tier1 = filter(scored, sp -> sp.type && sp.activity && sp.brand);
            tier1Source = "drop_gender";
        }
        if (tier1.size() < 3) {
            tier1 = filter(scored, sp -> sp.type && sp.activity);
            tier1Source = "drop_brand";
        }
        if (tier1.size() < 3) {
            tier1 = filter(scored, sp -> sp.type);
            tier1Source = "type_only";
        }
        tier1.sort(byComposite());
        tier1 = trim(tier1, 3);

        Set<Long> used = collectIds(tier1);

        // ============== TIER 2: type match, drop activity OR gender ==============
        List<ScoredProduct> tier2 = filter(scored, sp ->
                !used.contains(sp.product.getId())
                        && sp.type
                        && (sp.activity || sp.gender));
        tier2.sort(byComposite());
        tier2 = trim(tier2, 2);
        used.addAll(collectIds(tier2));

        boolean strictType = ctx.isStrictProductType()
                && ctx.getProductType() != null && !ctx.getProductType().isBlank();

        // ============== STRICT-TYPE FILL: same-type remainder up to target ==============
        // Under strictType, tier3/tier4 are skipped, so tier1(≤3)+tier2(≤2) caps output at 5
        // (and at 3 when activity & gender are both null → tier2 empty). Fill with the rest of
        // the type-matched candidates, best composite first, so we return up to TARGET_SIZE.
        List<ScoredProduct> tierFill = List.of();
        if (strictType) {
            int need = TARGET_SIZE - tier1.size() - tier2.size();
            if (need > 0) {
                tierFill = filter(scored, sp ->
                        !used.contains(sp.product.getId()) && sp.type);
                tierFill.sort(byComposite());
                tierFill = trim(tierFill, need);
                used.addAll(collectIds(tierFill));
            }
        }

        List<ScoredProduct> tier3 = List.of();
        List<ScoredProduct> tier4 = List.of();
        if (!strictType) {
            // ============== TIER 3: NOT type, but activity match ==============
            tier3 = filter(scored, sp ->
                    !used.contains(sp.product.getId())
                            && !sp.type
                            && sp.activity);
            tier3.sort(byComposite());
            tier3 = trim(tier3, 2);
            used.addAll(collectIds(tier3));

            // ============== TIER 4: best-seller fill from remaining ==============
            tier4 = filter(scored, sp -> !used.contains(sp.product.getId()));
            tier4.sort(Comparator.comparingInt((ScoredProduct s) -> {
                Integer ts = s.product.getTotalSold();
                return ts == null ? 0 : ts;
            }).reversed());
            tier4 = trim(tier4, 3);
        }

        System.out.println("🎯 Tier sizes: " + tier1.size() + "+" + tier2.size()
                + "+" + tier3.size() + "+" + tier4.size()
                + " | strictFill=" + tierFill.size()
                + " | tier1=" + tier1Source
                + " | strictType=" + strictType
                + " | productType=" + ctx.getProductType()
                + " | activity=" + ctx.getActivity()
                + " | topBrands=" + ctx.getTopBrands()
                + " | gender=" + ctx.getGenderPref());

        List<ScoredProduct> combined = new ArrayList<>();
        combined.addAll(tier1);
        combined.addAll(tier2);
        combined.addAll(tierFill);
        combined.addAll(tier3);
        combined.addAll(tier4);

        return combined.stream()
                .map(s -> Map.<ProductResponseDTO, Double>entry(s.product, s.composite))
                .collect(Collectors.toList());
    }

    // ============== Composite scoring helpers ==============

    private double computeComposite(boolean typeMatch, boolean activityMatch, boolean brandMatch,
                                    ProductResponseDTO p, double mlNorm, RankingContext ctx) {
        double s = 0;
        s += ctx.getWType() * (typeMatch ? 1.0 : 0.0);
        s += ctx.getWActivity() * (activityMatch ? 1.0 : 0.0);
        s += ctx.getWBrand() * (brandMatch ? 1.0 : 0.0);
        s += ctx.getWGender() * genderScore(p, ctx.getGenderPref());
        s += ctx.getWMl() * mlNorm;
        s += ctx.getWText() * 0.5;  // placeholder for query token overlap
        return s;
    }

    private boolean activityMatches(ProductResponseDTO p, Set<String> activityCats) {
        if (activityCats == null || activityCats.isEmpty() || p.getCategoryName() == null) return false;
        return activityCats.contains(p.getCategoryName());
    }

    private boolean brandMatches(ProductResponseDTO p, List<String> topBrands) {
        if (topBrands == null || topBrands.isEmpty() || p.getBrand() == null) return false;
        return topBrands.stream().anyMatch(b -> b.equalsIgnoreCase(p.getBrand()));
    }

    private boolean genderMatches(ProductResponseDTO p, String genderPref) {
        if (genderPref == null || p.getSex() == null) return false;
        String g = p.getSex().toUpperCase();
        String pref = genderPref.toUpperCase();
        String prefShort = pref.substring(0, Math.min(3, pref.length()));
        return g.startsWith(prefShort) || g.equals("UNISEX");
    }

    private double genderScore(ProductResponseDTO p, String genderPref) {
        if (genderPref == null) return 0.7;     // no pref → neutral
        if (p.getSex() == null) return 0.5;
        String g = p.getSex().toUpperCase();
        String pref = genderPref.toUpperCase();
        String prefShort = pref.substring(0, Math.min(3, pref.length()));
        if (g.startsWith(prefShort)) return 1.0;
        if (g.equals("UNISEX")) return 0.7;
        return 0.3;
    }

    // ============== Util ==============

    private Comparator<ScoredProduct> byComposite() {
        return Comparator.comparingDouble((ScoredProduct s) -> s.composite).reversed();
    }

    private <T> List<T> filter(List<T> list, Predicate<T> p) {
        return list.stream().filter(p).collect(Collectors.toCollection(ArrayList::new));
    }

    private <T> List<T> trim(List<T> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    private Set<Long> collectIds(List<ScoredProduct> list) {
        return list.stream().map(s -> s.product.getId()).collect(Collectors.toCollection(HashSet::new));
    }

    @AllArgsConstructor
    private static class ScoredProduct {
        final ProductResponseDTO product;
        final double composite;
        final boolean type;
        final boolean activity;
        final boolean brand;
        final boolean gender;
    }
}
