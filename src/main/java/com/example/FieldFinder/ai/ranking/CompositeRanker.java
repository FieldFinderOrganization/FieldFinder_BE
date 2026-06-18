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

    /** Default target result count returned to the AI chat (override per-query via RankingContext.targetSize). */
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

        // When the query explicitly names a brand (e.g. "balo adidas"), that brand is the
        // dominant signal: history top-brands are ignored (so they can't reorder the requested
        // brand) and the requested brand sorts first — soft, non-matching items still fill.
        boolean queryBrandActive = ctx.getQueryBrand() != null && !ctx.getQueryBrand().isBlank();
        // Màu user nêu thẳng (vd "giày nike màu đen") → khớp dominantColor (màu thật) là tín hiệu mạnh.
        boolean queryColorActive = ctx.getQueryColor() != null && !ctx.getQueryColor().isBlank();
        // Giới tính + size user nêu thẳng (vd "giày nike nam size 39") — cùng cơ chế soft với màu.
        boolean queryGenderActive = ctx.getQueryGender() != null && !ctx.getQueryGender().isBlank();
        boolean querySizeActive = ctx.getQuerySize() != null && !ctx.getQuerySize().isBlank();

        // Score each candidate
        List<ScoredProduct> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ProductResponseDTO p = candidates.get(i);
            double mlNorm = (mlScores.get(i) - minMl) / mlRange;
            boolean typeMatch = categoryService.productMatchesType(p, ctx.getProductType());
            boolean activityMatch = activityMatches(p, ctx.getActivityCats());
            // Brand score theo HẠNG trong topBrands (đã sort theo tần suất xem giảm dần):
            // brand hay xem nhất → điểm cao nhất. KHÔNG còn nhị phân (Nike == Adidas).
            // Khi query đã nêu brand → bỏ qua topBrands lịch sử (không để Nike chen lên adidas).
            double brandScore = brandRankScore(p, queryBrandActive ? List.of() : ctx.getTopBrands());
            boolean genderMatch = genderMatches(p, ctx.getGenderPref());
            boolean queryBrandMatch = queryBrandActive
                    && p.getBrand() != null
                    && p.getBrand().equalsIgnoreCase(ctx.getQueryBrand());
            // Mức khớp màu user nêu trên màu CHUẨN (dominantColor/colors, KHÔNG dùng tags nhiễu):
            // 2 = trùng dominantColor (màu thuần) · 1 = nằm trong colors (sp đa màu 50/50) · 0 = không.
            int queryColorScore = queryColorActive ? p.colorRank(ctx.getQueryColor()) : 0;
            // Giới user nêu: đúng giới = 2, UNISEX = 1 (vẫn mang được, xếp sau đồ đúng giới), khác = 0.
            int queryGenderScore = queryGenderActive ? queryGenderScore(p, ctx.getQueryGender()) : 0;
            // Size user nêu: còn variant đúng size VÀ còn hàng (quantity > 0).
            boolean querySizeMatch = querySizeActive && hasSizeInStock(p, ctx.getQuerySize());

            double composite = computeComposite(typeMatch, activityMatch, brandScore,
                    queryColorScore, p, mlNorm, ctx);
            scored.add(new ScoredProduct(p, composite, typeMatch, activityMatch, brandScore, genderMatch,
                    queryBrandMatch, queryColorScore, queryGenderScore, querySizeMatch));
        }

        boolean strictType = ctx.isStrictProductType()
                && ctx.getProductType() != null && !ctx.getProductType().isBlank();

        // Lexicographic priority — directly encodes the desired UX ordering instead of a
        // ≥3-or-collapse degrade chain (which dropped to "type only" whenever the queried
        // category was sparse, e.g. few basketball shoes in catalog):
        //   1. activity/category match (basketball shoes) first
        //   2. then preferred brand BY RANK (Nike viewed most > Adidas > K-Swiss)
        //   3. then gender fit (profile gender; unisex when no profile)
        //   4. then composite (ML/text relevance) as tiebreak
        //   1b. query-stated attributes ngay sau brand, thứ tự CỐ ĐỊNH brand → giới → màu → size
        //       (vd "giày nike nam màu đen size 39"). Mỗi tín hiệu soft: sai không loại, chỉ rớt
        //       xuống. No-op khi query không nêu thuộc tính tương ứng.
        Comparator<ScoredProduct> byPriority = Comparator
                .comparing((ScoredProduct s) -> s.queryBrand, Comparator.reverseOrder())     // 1. requested brand
                .thenComparing(s -> s.queryGenderScore, Comparator.reverseOrder())           // 2. requested gender (2/1/0)
                .thenComparing(s -> s.queryColor, Comparator.reverseOrder())                 // 3. requested color (2 thuần > 1 đa màu > 0)
                .thenComparing(s -> s.querySize, Comparator.reverseOrder())                  // 4. requested size in stock
                .thenComparing(s -> s.activity, Comparator.reverseOrder())
                .thenComparing(s -> s.brandScore, Comparator.reverseOrder())
                .thenComparing(s -> genderScore(s.product, ctx.getGenderPref()), Comparator.reverseOrder())
                .thenComparing(s -> s.composite, Comparator.reverseOrder());

        List<ScoredProduct> combined = new ArrayList<>();

        // Primary: type-matched products (e.g. all SHOES), priority-ordered, capped at target.
        int target = ctx.getTargetSize() > 0 ? ctx.getTargetSize() : TARGET_SIZE;
        List<ScoredProduct> typed = filter(scored, sp -> sp.type);
        typed.sort(byPriority);
        combined.addAll(trim(typed, target));

        if (!strictType) {
            Set<Long> used = collectIds(combined);
            // Cross-type activity matches (e.g. basketball clothing for a basketball query).
            List<ScoredProduct> offType = filter(scored, sp ->
                    !used.contains(sp.product.getId()) && !sp.type && sp.activity);
            offType.sort(byPriority);
            combined.addAll(trim(offType, 2));
            used.addAll(collectIds(offType));
            // Best-seller fill from remaining.
            List<ScoredProduct> bestSeller = filter(scored, sp -> !used.contains(sp.product.getId()));
            bestSeller.sort(Comparator.comparingInt((ScoredProduct s) -> {
                Integer ts = s.product.getTotalSold();
                return ts == null ? 0 : ts;
            }).reversed());
            combined.addAll(trim(bestSeller, 3));
        }

        System.out.println("🎯 Ranked: typed=" + typed.size()
                + " | returned=" + combined.size()
                + " | strictType=" + strictType
                + " | productType=" + ctx.getProductType()
                + " | activity=" + ctx.getActivity()
                + " | topBrands=" + ctx.getTopBrands()
                + " | queryBrand=" + ctx.getQueryBrand()
                + " | queryGender=" + ctx.getQueryGender()
                + " | queryColor=" + ctx.getQueryColor()
                + " | querySize=" + ctx.getQuerySize()
                + " | gender=" + ctx.getGenderPref());

        return combined.stream()
                .map(s -> Map.<ProductResponseDTO, Double>entry(s.product, s.composite))
                .collect(Collectors.toList());
    }

    // ============== Composite scoring helpers ==============

    private double computeComposite(boolean typeMatch, boolean activityMatch, double brandScore,
                                    int colorScore, ProductResponseDTO p, double mlNorm, RankingContext ctx) {
        double s = 0;
        s += ctx.getWType() * (typeMatch ? 1.0 : 0.0);
        s += ctx.getWActivity() * (activityMatch ? 1.0 : 0.0);
        s += ctx.getWBrand() * brandScore;
        // Màu thuần (2) full; sp đa màu (1) 0.6 → vẫn được điểm, xếp sau màu thuần.
        s += ctx.getWColor() * (colorScore == 2 ? 1.0 : colorScore == 1 ? 0.6 : 0.0);
        s += ctx.getWGender() * genderScore(p, ctx.getGenderPref());
        s += ctx.getWMl() * mlNorm;
        s += ctx.getWText() * 0.5;  // placeholder for query token overlap
        return s;
    }

    private boolean activityMatches(ProductResponseDTO p, Set<String> activityCats) {
        if (activityCats == null || activityCats.isEmpty() || p.getCategoryName() == null) return false;
        return activityCats.contains(p.getCategoryName());
    }

    /**
     * Brand preference score theo HẠNG trong topBrands (list đã sort theo tần suất xem giảm dần).
     * Brand đầu list (hay xem nhất) → 1.0; brand kế → giảm dần; ngoài list → 0.
     * Vd topBrands=[Nike, Adidas, K-Swiss] → Nike 1.0, Adidas 0.66, K-Swiss 0.33.
     */
    private double brandRankScore(ProductResponseDTO p, List<String> topBrands) {
        if (topBrands == null || topBrands.isEmpty() || p.getBrand() == null) return 0.0;
        for (int i = 0; i < topBrands.size(); i++) {
            if (topBrands.get(i).equalsIgnoreCase(p.getBrand())) {
                return 1.0 - (double) i / topBrands.size();
            }
        }
        return 0.0;
    }

    /**
     * Điểm khớp giới user nêu thẳng: đúng giới → 2, UNISEX → 1, sai giới/không rõ → 0.
     * Int thay boolean để unisex không bị đánh đồng với sai giới (giày unisex vẫn mang được).
     */
    public static int queryGenderScore(ProductResponseDTO p, String queryGender) {
        if (queryGender == null || p.getSex() == null) return 0;
        String g = p.getSex().toUpperCase();
        String pref = queryGender.toUpperCase();
        String prefShort = pref.substring(0, Math.min(3, pref.length()));
        if (g.startsWith(prefShort)) return 2;
        if (g.equals("UNISEX")) return 1;
        return 0;
    }

    /**
     * True nếu sản phẩm có variant đúng size (so chuỗi, bỏ hoa thường) và còn hàng.
     * Variant FREESIZE/ONE SIZE còn hàng được coi là khớp MỌI size (đồ một-size vừa tất cả).
     */
    public static boolean hasSizeInStock(ProductResponseDTO p, String size) {
        if (size == null || size.isBlank() || p.getVariants() == null) return false;
        for (ProductResponseDTO.VariantDTO v : p.getVariants()) {
            boolean inStock = v.getQuantity() != null && v.getQuantity() > 0;
            if (!inStock || v.getSize() == null) continue;
            String vs = v.getSize().trim();
            if (vs.equalsIgnoreCase(size.trim()) || isFreeSize(vs)) return true;
        }
        return false;
    }

    /** Size một-cỡ (freesize/one size/OS/F) — vừa cho mọi yêu cầu size. */
    public static boolean isFreeSize(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase().replace("-", "").replace("_", "").replace(" ", "");
        return t.equals("freesize") || t.equals("free") || t.equals("onesize") || t.equals("os") || t.equals("f");
    }

    /** True nếu sản phẩm còn hàng ở ÍT NHẤT 1 variant. Không có variant → coi như còn (không loại oan). */
    public static boolean hasAnyStock(ProductResponseDTO p) {
        if (p.getVariants() == null || p.getVariants().isEmpty()) return true;
        for (ProductResponseDTO.VariantDTO v : p.getVariants()) {
            if (v.getQuantity() != null && v.getQuantity() > 0) return true;
        }
        return false;
    }

    private boolean genderMatches(ProductResponseDTO p, String genderPref) {
        if (genderPref == null || p.getSex() == null) return false;
        String g = p.getSex().toUpperCase();
        String pref = genderPref.toUpperCase();
        String prefShort = pref.substring(0, Math.min(3, pref.length()));
        return g.startsWith(prefShort) || g.equals("UNISEX");
    }

    private double genderScore(ProductResponseDTO p, String genderPref) {
        if (genderPref == null) {
            // No profile gender → mild preference for unisex (broadest fit, safest first slot).
            if (p.getSex() == null) return 0.6;
            return p.getSex().equalsIgnoreCase("UNISEX") ? 0.8 : 0.6;
        }
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
        final double brandScore;   // theo hạng trong topBrands (1.0 = hay xem nhất, 0 = ngoài list)
        final boolean gender;
        final boolean queryBrand;  // true nếu khớp brand user nêu thẳng trong query (balo "adidas")
        final int queryColor;      // mức khớp màu user nêu: 2 dominant (thuần), 1 trong colors (đa màu), 0 không
        final int queryGenderScore; // 2 đúng giới user nêu, 1 unisex, 0 sai/không nêu
        final boolean querySize;   // true nếu còn variant đúng size user nêu ("39") và còn hàng
    }
}
