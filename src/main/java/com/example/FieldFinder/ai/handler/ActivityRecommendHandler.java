package com.example.FieldFinder.ai.handler;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.ai.AiChatSessionContextStore;
import com.example.FieldFinder.ai.cache.AiCatalogCache;
import com.example.FieldFinder.ai.gemini.GeminiClient;
import com.example.FieldFinder.ai.match.AiProductMatch;
import com.example.FieldFinder.ai.ranking.CompositeRanker;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.dto.res.MLItemResult;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.mapper.CategoryMapper;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Intent gợi ý sản phẩm theo hoạt động/nhu cầu (recommend_by_activity) — tách khỏi AIChat.
 * Logic giữ nguyên 1:1. Có thể pivot sang product-query qua ProductQueryHandler.
 */
@Component
public class ActivityRecommendHandler {

    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiCatalogCache catalogCache;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final AiChatSessionContextStore sessionContextStore;
    private final MLRecommendationService mlService;
    private final LogPublisherService logPublisherService;
    private final GeminiClient geminiClient;
    private final CompositeRanker compositeRanker;
    private final UserService userService;
    private final ProductQueryHandler productQueryHandler;

    public ActivityRecommendHandler(AiCatalogCache catalogCache, ProductService productService,
                                    CategoryService categoryService, AiChatSessionContextStore sessionContextStore,
                                    MLRecommendationService mlService, LogPublisherService logPublisherService,
                                    GeminiClient geminiClient, CompositeRanker compositeRanker,
                                    UserService userService, ProductQueryHandler productQueryHandler) {
        this.catalogCache = catalogCache;
        this.productService = productService;
        this.categoryService = categoryService;
        this.sessionContextStore = sessionContextStore;
        this.mlService = mlService;
        this.logPublisherService = logPublisherService;
        this.geminiClient = geminiClient;
        this.compositeRanker = compositeRanker;
        this.userService = userService;
        this.productQueryHandler = productQueryHandler;
    }

    @SuppressWarnings("unchecked")
    public AIChat.BookingQuery handle(AIChat.BookingQuery query, String sessionId, String userInput) {
        UUID userId = catalogCache.resolveCurrentUserId(sessionId);
        String activity = (String) query.data.get("activity");
        List<String> tags = (List<String>) query.data.get("tags");
        List<String> aiCategories = (List<String>) query.data.get("suggestedCategories");
        String aiProductType = AiProductMatch.normalizeAiProductType(query.data.get("productType"));
        String categoryKeyword = (String) query.data.get("categoryKeyword");

        System.out.println("🟢 recommend_by_activity | userInput='" + userInput + "'");
        System.out.println("   AI parsed: action=" + query.data.get("action")
                + " categoryKeyword=" + query.data.get("categoryKeyword")
                + " activity=" + activity
                + " productType=" + aiProductType
                + " tags=" + tags
                + " suggestedCategories=" + aiCategories);

        if (activity != null && sessionId != null) {
            sessionContextStore.setLastActivity(sessionId, activity);
        }
        if (sessionId != null) {
            String catToStore = categoryKeyword;
            if (aiCategories != null && !aiCategories.isEmpty()) {
                String specific = aiCategories.get(0);
                if (specific != null && !specific.isBlank()) catToStore = specific;
            }
            if (catToStore != null && !catToStore.isBlank()) {
                sessionContextStore.setLastCategoryKeyword(sessionId, catToStore);
            }
            if (aiProductType != null && !aiProductType.isBlank()) {
                sessionContextStore.setLastProductType(sessionId, aiProductType);
            }
        }

        if (tags == null || tags.isEmpty()) {
            tags = (activity != null) ? List.of(activity) : List.of("sport");
        }

        // Build description: prepend categoryKeyword × 3 (text repetition → FAISS embed lean về category)
        // Tránh trường hợp query "giày bóng rổ" trả ra Basketball Clothing
        List<String> descParts = new ArrayList<>();
        if (categoryKeyword != null && !categoryKeyword.isEmpty()) {
            descParts.add(categoryKeyword);
            descParts.add(categoryKeyword);
            descParts.add(categoryKeyword);
        }
        if (activity != null) descParts.add(activity);
        if (tags != null) descParts.addAll(tags);
        String description = String.join(" ", descParts).trim();

        // Try ML retrieve first (Personalized RAG); fallback to local vector search
        String retrievalSource = "VECTOR_LOCAL";
        List<ProductResponseDTO> results = null;
        List<Double> retrievalScores = null;

        List<MLItemResult> mlHits = description.isEmpty()
                ? null
                : mlService.retrieve(description, userId != null ? userId.toString() : null, 20, "PRODUCT");
        System.out.println("🟢 ML retrieve hits: " + (mlHits != null ? mlHits.size() : "null"));
        if (mlHits != null && !mlHits.isEmpty()) {
            results = new ArrayList<>();
            retrievalScores = new ArrayList<>();

            // Batch fetch — avoid N+1 query (each getProductById = 3+ Hibernate queries)
            List<Long> pids = new ArrayList<>();
            for (MLItemResult h : mlHits) {
                try { pids.add(Long.parseLong(h.getItemId())); } catch (NumberFormatException ignored) {}
            }
            Map<Long, ProductResponseDTO> productMap = productService.getProductsByIds(pids, userId);

            int matched = 0, missing = 0;
            for (MLItemResult h : mlHits) {
                Long pid;
                try { pid = Long.parseLong(h.getItemId()); }
                catch (NumberFormatException e) {
                    System.out.println("  ⚠ ML invalid product_id: " + h.getItemId());
                    continue;
                }
                ProductResponseDTO p = productMap.get(pid);
                if (p != null) {
                    results.add(p);
                    retrievalScores.add(h.getFinalScore() != null ? h.getFinalScore() : (h.getScore() != null ? h.getScore() : 0.0));
                    matched++;
                } else {
                    missing++;
                    System.out.println("  ⚠ ML returned product_id=" + pid + " not in DB");
                }
            }
            System.out.println("🟢 ML match: " + matched + " / " + mlHits.size() + " (missing=" + missing + ")");
            if (!results.isEmpty()) {
                retrievalSource = "ML_RAG";
            }
        }

        if (retrievalSource.equals("VECTOR_LOCAL")) {
            List<Map.Entry<ProductResponseDTO, Double>> scoredResults = productService.findProductsByVectorWithScores(description);
            results = scoredResults.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            retrievalScores = scoredResults.stream().map(Map.Entry::getValue).collect(Collectors.toList());
        }

        String detectedType = aiProductType != null
                ? aiProductType
                : categoryService.detectProductTypeFromQuery(userInput, tags, categoryKeyword);
        List<String> resolvedCategories = CategoryMapper.resolveCategories(activity, aiCategories, categoryKeyword);

        // Thuộc tính user nêu thẳng trong query — dùng cho cả ranking lẫn tiered message cuối flow.
        String queryBrand = AiProductMatch.detectQueryBrand(catalogCache.getProductsForAiAssistantCached(userId), (String) query.data.get("productName"), tags, userInput);
        String queryColor = AiProductMatch.detectQueryColor(query.data.get("color"), userInput);
        String queryGender = AiProductMatch.detectQueryGender(tags, userInput);
        String querySize = AiProductMatch.detectQuerySize(query.data.get("size"), userInput);

        // Khoảng giá (query trộn "giày nike đen dưới 2 triệu" được reroute về flow này kèm minPrice/maxPrice).
        Object minPObj = query.data.get("minPrice");
        Object maxPObj = query.data.get("maxPrice");
        final double minPriceQ = minPObj instanceof Number n ? Math.max(n.doubleValue(), 0.0) : 0.0;
        double maxRaw = maxPObj instanceof Number n ? n.doubleValue() : 0.0;
        final double maxPriceQ = maxRaw > 0 ? maxRaw : Double.MAX_VALUE;
        final boolean priceActive = minPriceQ > 0 || maxRaw > 0;
        System.out.println("   resolved: detectedType=" + detectedType
                + " (source=" + (aiProductType != null ? "AI" : "JAVA") + ")"
                + " resolvedCategories=" + resolvedCategories);

        // Guarantee category candidates: the bi-encoder can miss in-category items whose name
        // is a model code with no category words (e.g. "LeBron XXII EP" → Basketball Shoes).
        // Pull every product in the resolved category straight from DB and union into the
        // candidate set (score 0.0) so the composite ranker can surface them.
        if (resolvedCategories != null && !resolvedCategories.isEmpty()) {
            if (results == null) {
                results = new ArrayList<>();
                retrievalScores = new ArrayList<>();
            } else {
                results = new ArrayList<>(results);
                retrievalScores = new ArrayList<>(retrievalScores);
            }
            Set<Long> haveIds = results.stream()
                    .map(ProductResponseDTO::getId).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            int added = 0;
            for (ProductResponseDTO p : catalogCache.getProductsForAiAssistantCached(userId)) {
                if (p.getId() == null || haveIds.contains(p.getId())) continue;
                // Union by category name OR detected type. Type match (via productMatchesType)
                // covers generic queries: "Shoes" → mọi subcat (Running/Football/Basketball Shoes),
                // nên pool không sụp về 2 khi ML down (circuit open → không có ML hits).
                boolean catHit = p.getCategoryName() != null
                        && resolvedCategories.contains(p.getCategoryName());
                boolean typeHit = detectedType != null && !detectedType.isBlank()
                        && categoryService.productMatchesType(p, detectedType);
                if (catHit || typeHit) {
                    results.add(p);
                    retrievalScores.add(0.0);
                    haveIds.add(p.getId());
                    added++;
                }
            }
            if (added > 0) {
                System.out.println("➕ Category augment: +" + added + " from " + resolvedCategories);
                retrievalSource = "ML_RAG+CAT";
            }
        }

        // Hard filter giá TRƯỚC ranking — để tier fill toàn sản phẩm trong tầm giá.
        if (priceActive && results != null && !results.isEmpty()) {
            List<ProductResponseDTO> inRange = new ArrayList<>();
            List<Double> inRangeScores = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                double ep = AiTextUtil.effectivePrice(results.get(i));
                if (ep >= minPriceQ && ep <= maxPriceQ) {
                    inRange.add(results.get(i));
                    inRangeScores.add(i < retrievalScores.size() ? retrievalScores.get(i) : 0.0);
                }
            }
            System.out.println("💰 Price filter " + AiTextUtil.buildPriceRangeMessage(minPriceQ, maxPriceQ)
                    + ": " + inRange.size() + "/" + results.size());
            results = inRange;
            retrievalScores = inRangeScores;
        }

        // Composite rank + strict type filter (e.g. "giày đá bóng" → chỉ SHOES, không fill quần tier 3/4)
        if (results != null && !results.isEmpty()) {
            // B.3: brand preference từ MongoDB user history (top 3)
            List<String> topBrands = userService.getUserTopBrands(userId, 3);

            boolean strictType = detectedType != null && !detectedType.isBlank();
            com.example.FieldFinder.ai.ranking.RankingContext ctx =
                    com.example.FieldFinder.ai.ranking.RankingContext.builder()
                            .productType(detectedType)
                            .activity(activity)
                            .genderPref(queryGender)
                            .topBrands(topBrands)
                            .queryBrand(queryBrand)
                            .queryColor(queryColor)
                            .queryGender(queryGender)
                            .querySize(querySize)
                            .activityCats(new HashSet<>(resolvedCategories))
                            .strictProductType(strictType)
                            // Query nêu size → lấy hết tier 1 để tiered grouping thấy đủ ứng viên
                            // (mẫu brand khác còn size rank sau toàn bộ sp đúng brand). Trim hiển thị sau grouping.
                            .targetSize(querySize != null ? Integer.MAX_VALUE : 10)
                            .build();

            List<Map.Entry<ProductResponseDTO, Double>> ranked =
                    compositeRanker.rank(results, retrievalScores, ctx);

            results = ranked.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            retrievalScores = ranked.stream().map(Map.Entry::getValue).collect(Collectors.toList());

            if (strictType) {
                AiProductMatch.StrictTypeFilterResult filtered =
                        AiProductMatch.strictTypeFilter(results, retrievalScores, detectedType, categoryService);
                results = filtered.products();
                retrievalScores = filtered.scores();
                System.out.println("🔒 Strict type filter (" + detectedType + "): " + results.size() + " products");
            }
        }

        if ((results == null || results.isEmpty()) && !resolvedCategories.isEmpty()) {
            final String typeForFallback = detectedType;
            results = catalogCache.getProductsForAiAssistantCached(userId).stream()
                    .filter(p -> p.getCategoryName() != null &&
                            resolvedCategories.contains(p.getCategoryName()))
                    .filter(p -> typeForFallback == null
                            || categoryService.productMatchesType(p, typeForFallback))
                    .filter(p -> !priceActive
                            || (AiTextUtil.effectivePrice(p) >= minPriceQ && AiTextUtil.effectivePrice(p) <= maxPriceQ))
                    .limit(12)
                    .toList();
            retrievalScores = Collections.nCopies(results.size(), 0.0); // No vector scores for category fallback
        }

        // Last resort: catalog-wide name/type scan. Surfaces items mis-filed by category,
        // e.g. skirts filed under "Shorts" but named "…Skirt" → khớp DRESS qua tên (productMatchesType 0a).
        if ((results == null || results.isEmpty()) && detectedType != null && !detectedType.isBlank()) {
            final String typeForScan = detectedType;
            results = catalogCache.getProductsForAiAssistantCached(userId).stream()
                    .filter(p -> categoryService.productMatchesType(p, typeForScan))
                    .filter(p -> !priceActive
                            || (AiTextUtil.effectivePrice(p) >= minPriceQ && AiTextUtil.effectivePrice(p) <= maxPriceQ))
                    .limit(12)
                    .toList();
            retrievalScores = Collections.nCopies(results.size(), 0.0);
        }

        // Lọc bỏ sản phẩm đã hết sạch hàng (mọi variant = 0) — không gợi ý sp không mua được.
        // Giữ song song results + retrievalScores. SP chưa có variant không bị loại (hasAnyStock=true).
        if (results != null && !results.isEmpty()) {
            List<ProductResponseDTO> inStock = new ArrayList<>();
            List<Double> inStockScores = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                if (com.example.FieldFinder.ai.ranking.CompositeRanker.hasAnyStock(results.get(i))) {
                    inStock.add(results.get(i));
                    inStockScores.add(i < retrievalScores.size() ? retrievalScores.get(i) : 0.0);
                }
            }
            int dropped = results.size() - inStock.size();
            if (dropped > 0) System.out.println("📦 Lọc hết hàng: bỏ " + dropped + " sp, còn " + inStock.size());
            results = inStock;
            retrievalScores = inStockScores;
        }

        boolean preferLowPrice = Boolean.TRUE.equals(query.data.get("preferLowPrice"))
                || AiTextUtil.isAffordableListQuery(userInput);
        if (preferLowPrice && results != null && results.size() > 1) {
            final List<ProductResponseDTO> toSort = results;
            final List<Double> scoresToSort = retrievalScores;
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < toSort.size(); i++) indices.add(i);
            indices.sort(Comparator.comparingDouble(i -> AiTextUtil.effectivePrice(toSort.get(i))));
            List<ProductResponseDTO> sorted = new ArrayList<>();
            List<Double> sortedScores = new ArrayList<>();
            for (int i : indices) {
                sorted.add(toSort.get(i));
                sortedScores.add(i < scoresToSort.size() ? scoresToSort.get(i) : 0.0);
            }
            results = sorted;
            retrievalScores = sortedScores;
            System.out.println("💰 preferLowPrice: sorted " + results.size() + " products by ascending price");
        }

        if (results == null || results.isEmpty()) {
            query.message = priceActive
                    ? String.format("Không tìm thấy sản phẩm phù hợp trong khoảng giá %s.",
                            AiTextUtil.buildPriceRangeMessage(minPriceQ, maxPriceQ))
                    : "Hiện tại shop chưa có sản phẩm phù hợp hoạt động này 😢";
            query.data.put("products", List.of());
            query.data.put("groupedProducts", Map.of());
            query.data.put("action", "recommend_by_activity");
            query.data.put("showImage", false);
            return query;
        }

        // ===== Tiered messaging theo mức khớp tiêu chí user nêu (brand/giới/màu/size) =====
        // Pattern constraint-relaxation-with-transparency: nói rõ tiêu chí nào hụt, đưa thay thế gần nhất.
        String genderVN = "WOMEN".equals(queryGender) ? "nữ" : ("MEN".equals(queryGender) ? "nam" : null);
        boolean hasCriteria = queryBrand != null || genderVN != null || queryColor != null || querySize != null;
        String tieredMessage = null;
        if (hasCriteria) {
            List<Integer> exactIdx = new ArrayList<>();
            List<Integer> otherSizeIdx = new ArrayList<>(); // nhóm 1: đúng mọi tiêu chí khác, hết size
            List<Integer> sizeAltIdx = new ArrayList<>();   // nhóm 2: còn đúng size nhưng lệch tiêu chí khác (vd brand khác)
            for (int i = 0; i < results.size(); i++) {
                ProductResponseDTO p = results.get(i);
                boolean brandOk = queryBrand == null
                        || (p.getBrand() != null && p.getBrand().equalsIgnoreCase(queryBrand));
                // UNISEX (score 1) vẫn tính là thỏa giới — mang được, không phải xin lỗi.
                boolean genderOk = queryGender == null
                        || com.example.FieldFinder.ai.ranking.CompositeRanker.queryGenderScore(p, queryGender) >= 1;
                boolean colorOk = queryColor == null
                        || (p.getDominantColor() != null && p.getDominantColor().equalsIgnoreCase(queryColor));
                boolean sizeOk = querySize == null
                        || com.example.FieldFinder.ai.ranking.CompositeRanker.hasSizeInStock(p, querySize);
                boolean nonSizeOk = brandOk && genderOk && colorOk;
                if (nonSizeOk && sizeOk) {
                    exactIdx.add(i);
                } else if (querySize != null && nonSizeOk) {
                    otherSizeIdx.add(i); // !sizeOk — đảm bảo nhóm 1 không sp nào còn size yêu cầu
                } else if (querySize != null
                        && com.example.FieldFinder.ai.ranking.CompositeRanker.hasSizeInStock(p, querySize)) {
                    sizeAltIdx.add(i);
                }
            }
            String criteriaDesc = AiTextUtil.buildCriteriaDesc(detectedType, queryBrand, genderVN, queryColor);
            if (!exactIdx.isEmpty()) {
                StringBuilder mb = new StringBuilder(String.format("Tìm thấy %d sản phẩm %s%s đúng yêu cầu của bạn",
                        exactIdx.size(), criteriaDesc, querySize != null ? " size " + querySize : ""));
                if (querySize != null) {
                    // Tầng 1 có size: chỉ giữ sp CÒN size yêu cầu — exact trước, bù mẫu khác còn size
                    // (sizeAlt) cho đỡ thưa. Không để lọt sp hết size vào list dù ranker xếp cao.
                    List<Integer> keep = new ArrayList<>(exactIdx.subList(0, Math.min(10, exactIdx.size())));
                    int altCap = Math.min(Math.min(5, 10 - keep.size()), sizeAltIdx.size());
                    if (altCap > 0) {
                        keep.addAll(sizeAltIdx.subList(0, altCap));
                        mb.append(", kèm vài mẫu tương tự cũng còn size ").append(querySize);
                    }
                    List<ProductResponseDTO> tierResults = new ArrayList<>();
                    List<Double> tierScores = new ArrayList<>();
                    for (int i : keep) {
                        tierResults.add(results.get(i));
                        tierScores.add(i < retrievalScores.size() ? retrievalScores.get(i) : 0.0);
                    }
                    results = tierResults;
                    retrievalScores = tierScores;
                }
                mb.append(" 👇");
                tieredMessage = mb.toString();
            } else if (querySize != null && (!otherSizeIdx.isEmpty() || !sizeAltIdx.isEmpty())) {
                // Tầng 2: hết size — show nhóm 1 (size khác) + nhóm 2 (mẫu khác còn size), mỗi nhóm giữ thứ tự ranked.
                List<Integer> keep = new ArrayList<>();
                keep.addAll(otherSizeIdx.subList(0, Math.min(5, otherSizeIdx.size())));
                keep.addAll(sizeAltIdx.subList(0, Math.min(5, sizeAltIdx.size())));
                List<ProductResponseDTO> tierResults = new ArrayList<>();
                List<Double> tierScores = new ArrayList<>();
                for (int i : keep) {
                    tierResults.add(results.get(i));
                    tierScores.add(i < retrievalScores.size() ? retrievalScores.get(i) : 0.0);
                }
                results = tierResults;
                retrievalScores = tierScores;
                StringBuilder mb = new StringBuilder("Xin lỗi bạn, ").append(criteriaDesc)
                        .append(" hiện đã hết size ").append(querySize).append(".");
                if (!otherSizeIdx.isEmpty() && !sizeAltIdx.isEmpty()) {
                    mb.append(" Bạn tham khảo các size khác, hoặc các mẫu tương tự còn size ")
                      .append(querySize).append(" bên dưới nhé 👇");
                } else if (!otherSizeIdx.isEmpty()) {
                    mb.append(" Bạn vui lòng tham khảo các size khác bên dưới nhé 👇");
                } else {
                    mb.append(" Bạn tham khảo các mẫu tương tự còn size ").append(querySize).append(" bên dưới nhé 👇");
                }
                tieredMessage = mb.toString();
            } else {
                // Tầng 3: không sp nào khớp đủ tiêu chí → best-effort relaxed list (đã ranked gần nhất lên đầu).
                tieredMessage = "Xin lỗi bạn, hiện shop không còn sản phẩm nào phù hợp với yêu cầu của bạn, "
                        + "bạn vui lòng tham khảo các sản phẩm tương tự 👇";
            }
            System.out.println("💬 Tiered message: exact=" + exactIdx.size()
                    + " otherSize=" + otherSizeIdx.size() + " sizeAlt=" + sizeAltIdx.size());
        }

        // ===== Trung thực khi loại/hoạt động CỤ THỂ không (hoặc ít) có hàng thật =====
        // User hỏi "giày trượt băng"/"chân váy" → nếu không có sp đúng category cụ thể đó,
        // KHÔNG trình bày sp generic như thể khớp; nói rõ + đưa làm "tương tự".
        java.util.Set<String> broadCats = java.util.Set.of("shoes", "clothing", "footwear", "accessories");
        List<String> specificCats = (aiCategories == null) ? List.of() : aiCategories.stream()
                .filter(c -> c != null && !c.isBlank() && !broadCats.contains(c.trim().toLowerCase()))
                .collect(Collectors.toList());
        String categoryMessage = null;
        if (!specificCats.isEmpty()) {
            java.util.Set<String> wanted = specificCats.stream()
                    .map(c -> c.trim().toLowerCase()).collect(Collectors.toSet());
            List<ProductResponseDTO> genuine = results.stream()
                    .filter(p -> p.getCategoryName() != null && wanted.contains(p.getCategoryName().trim().toLowerCase()))
                    .collect(Collectors.toList());
            String label = (activity != null && !activity.isBlank() && !"null".equalsIgnoreCase(activity))
                    ? "cho hoạt động " + activity
                    : "loại \"" + AiTextUtil.translateCategory(specificCats.get(0)) + "\"";
            if (genuine.isEmpty()) {
                categoryMessage = String.format(
                        "Chúng mình hiện chưa có sản phẩm %s, bạn vui lòng tham khảo các sản phẩm tương tự bên dưới nhé 👇", label);
            } else if (genuine.size() < results.size()) {
                // Đưa sp đúng loại lên đầu, phần còn lại là "tương tự".
                List<ProductResponseDTO> reordered = new ArrayList<>(genuine);
                for (ProductResponseDTO p : results) if (!genuine.contains(p)) reordered.add(p);
                results = reordered;
                categoryMessage = String.format(
                        "Tìm thấy %d sản phẩm %s, kèm vài mẫu tương tự bên dưới nhé 👇", genuine.size(), label);
            }
        }

        if (tieredMessage != null) {
            query.message = tieredMessage;
        } else if (preferLowPrice) {
            String catLabel = categoryKeyword != null ? AiTextUtil.translateCategory(categoryKeyword) : null;
            query.message = (catLabel != null && !catLabel.isBlank())
                    ? String.format("Đây là các sản phẩm %s giá mềm hơn bạn có thể tham khảo 👇", catLabel)
                    : "Đây là các sản phẩm giá mềm hơn bạn có thể tham khảo 👇";
        } else if (categoryMessage != null) {
            query.message = categoryMessage;
        } else if (activity != null && !activity.isBlank() && !"null".equalsIgnoreCase(activity)) {
            query.message = String.format("Với hoạt động %s, bạn có thể tham khảo các sản phẩm sau 👇", activity);
        } else {
            query.message = "Bạn có thể tham khảo các sản phẩm sau 👇";
        }

        // Trim hiển thị về 10 SAU tiered grouping (ranker trả sâu hơn khi query nêu size;
        // tầng 2 đã tự cap 5+5, các tầng khác cắt tại đây — exact match vẫn đứng đầu nhờ comparator).
        if (results.size() > 10) {
            results = results.subList(0, 10);
            retrievalScores = retrievalScores.subList(0, Math.min(10, retrievalScores.size()));
        }

        Map<String, List<Map<String, Object>>> groupedProducts = new LinkedHashMap<>();

        for (ProductResponseDTO p : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("product", p);

            Map<String, Object> facts = new HashMap<>();
            facts.put("activity", activity);
            facts.put("category", p.getCategoryName());
            facts.put("description", p.getDescription());
            facts.put("tags", p.getTags());
            facts.put("brand", p.getBrand());
            facts.put("price", p.getPrice());
            facts.put("salePercent", p.getSalePercent());
            facts.put("totalSold", p.getTotalSold());
            facts.put("stock", p.getStockQuantity());

            item.put("facts", facts);

            String categoryKey = p.getCategoryName() != null ? p.getCategoryName() : "OTHER";
            groupedProducts.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(item);
        }

        query.data.put("groupedProducts", groupedProducts);
        query.data.put("products", results);
        query.data.put("explainContext", Map.of("style", "sales_consultant", "maxReasonLength", 25));
        query.data.put("action", "recommend_by_activity");
        query.data.put("showImage", true);

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("activity", activity);
            metadata.put("tags", tags);
            metadata.put("aiResponseText", query.message);
            metadata.put("modelVersion", MODEL_VERSION);
            metadata.put("result_count", results.size());
            // Log retrievedItemIds + scores for ML
            List<Long> retrievedIds = results.stream()
                    .map(ProductResponseDTO::getId)
                    .collect(Collectors.toList());
            metadata.put("retrievedItemIds", retrievedIds);
            metadata.put("retrievalScore", retrievalScores);
            metadata.put("retrievalSource", retrievalSource);
            logPublisherService.publishEvent(
                    userId != null ? userId.toString() : null,
                    sessionId,
                    "CHAT_ACTIVITY_RECOMMEND",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_ACTIVITY_RECOMMEND: " + e.getMessage());
        }

        return query;
    }
}
