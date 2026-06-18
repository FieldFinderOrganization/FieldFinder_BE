package com.example.FieldFinder.ai.handler;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.ai.AiChatSessionContextStore;
import com.example.FieldFinder.ai.cache.AiCatalogCache;
import com.example.FieldFinder.ai.gemini.GeminiClient;
import com.example.FieldFinder.ai.match.AiProductMatch;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.dto.req.MLRetrieveByImageRequest;
import com.example.FieldFinder.dto.res.MLItemResult;
import com.example.FieldFinder.dto.res.MLRetrieveResponse;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.PhashIndex;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.example.FieldFinder.util.ColorVocab;
import com.example.FieldFinder.util.PhashUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Intent tìm-kiếm-ảnh của trợ lý AI — tách khỏi AIChat.
 * Pipeline: Stage 0 pHash near-dup → Stage 1 Gemini Vision + ML CLIP (RRF, type-gate, brand backfill,
 * attribute rerank, pin exact) → Stage 2 fallback vector/tag. Logic giữ nguyên 1:1 so với bản cũ.
 */
@Component
public class ImageSearchHandler {

    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private static final String IMAGE_ANALYSIS_SYSTEM_PROMPT = """
        Bạn là chuyên gia thời trang (Sneakerhead).
        Nhiệm vụ: Phân tích ảnh để tìm kiếm sản phẩm.

        1. XÁC ĐỊNH LOẠI SẢN PHẨM (`majorCategory`):
        - `FOOTWEAR` (Giày, Dép), `CLOTHING` (Quần, Áo, Váy), `ACCESSORY` (Balo, Nón, Túi...).

        2. XÁC ĐỊNH LOẠI CỤ THỂ (`productType`) — BẮT BUỘC chọn đúng 1:
        - `SHOES` — giày thể thao, giày tây, sneaker, boot
        - `SANDAL` — dép, sandal
        - `TOP` — áo (T-shirt, polo, hoodie, jacket, sơ mi)
        - `BOTTOM` — quần (short, jeans, kaki, jogger)
        - `DRESS` — váy, đầm
        - `BAG` — balo, túi xách, túi đeo chéo
        - `HAT` — nón, mũ, cap, beanie
        - `OTHER` — phụ kiện khác (kính, găng tay, vớ...)

        3. PHÂN TÍCH MÀU SẮC (RẤT QUAN TRỌNG):
        - Đừng chỉ chọn 1 màu. Hãy liệt kê TẤT CẢ màu sắc nhìn thấy.
        - Phân biệt: Màu chủ đạo (Dominant) và Màu phối (Accent).
        - Ví dụ: Giày trắng logo đỏ -> Tags phải có cả "trắng", "white", "đỏ", "red".
        - Các màu tương đồng: Nếu thấy "kem/cream/beige" -> Hãy thêm tag "trắng/white". Nếu thấy "xanh dương/navy" -> Thêm tag "xanh/blue".

        4. ĐỌC CHỮ (OCR):
        - Cố gắng đọc tên dòng sản phẩm trên thân/lưỡi gà (VD: Air Max, Jordan, Ultraboost).

        YÊU CẦU OUTPUT JSON:
        {
          "majorCategory": "FOOTWEAR",
          "productType": "SHOES",
          "productName": "Tên gợi ý (VD: Nike Air Max 1 White/Orange)",
          "color": "Mô tả màu (VD: Trắng phối Cam)",
          "tags": ["danh sách tags: nike, air max, trắng, white, cam, orange, giày, sneaker..."]
        }
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiClient geminiClient;
    private final MLRecommendationService mlService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final PhashIndex phashIndex;
    private final RedisService redisService;
    private final AiChatSessionContextStore sessionContextStore;
    private final LogPublisherService logPublisherService;
    private final AiCatalogCache catalogCache;

    public ImageSearchHandler(GeminiClient geminiClient, MLRecommendationService mlService,
                              ProductService productService, CategoryService categoryService,
                              PhashIndex phashIndex, RedisService redisService,
                              AiChatSessionContextStore sessionContextStore,
                              LogPublisherService logPublisherService, AiCatalogCache catalogCache) {
        this.geminiClient = geminiClient;
        this.mlService = mlService;
        this.productService = productService;
        this.categoryService = categoryService;
        this.phashIndex = phashIndex;
        this.redisService = redisService;
        this.sessionContextStore = sessionContextStore;
        this.logPublisherService = logPublisherService;
        this.catalogCache = catalogCache;
    }

    public AIChat.BookingQuery process(String base64Image, String sessionId) {
        final long _tStart = System.currentTimeMillis();
        Runnable _logTotal = () -> System.out.println("[IMG-TIMING] processImageSearchWithGemini TOTAL="
                + (System.currentTimeMillis() - _tStart) + "ms");
        AIChat.BookingQuery result = new AIChat.BookingQuery();
        result.data = new HashMap<>();
        result.slotList = new ArrayList<>();
        result.pitchType = "ALL";

        // In-shop exact match to pin at result #0 (pHash near-dup OR CLIP high-conf).
        // Captured in Stage 0, applied before returning Stage 1 / Stage 2 results.
        Long pinnedPid = null;
        ProductResponseDTO pinnedDto = null;
        double pinnedScore = 0.0;

        // ========== Stage 0: pHash near-duplicate match ==========
        long _t0 = System.currentTimeMillis();
        Long uploadHash = PhashUtil.computeFromBase64(base64Image);
        System.out.println("[IMG-TIMING] pHash compute=" + (System.currentTimeMillis() - _t0) + "ms");
        System.out.println("🔍 pHash debug: uploadHash=" + uploadHash + " indexSize=" + phashIndex.size());
        if (uploadHash != null && phashIndex.size() > 0) {
            List<PhashIndex.Hit> debugTop = phashIndex.findWithin(uploadHash, 64, 3);
            System.out.println("🔍 pHash top3 distances: " + debugTop.stream()
                    .map(h -> h.productId + "=" + h.distance)
                    .collect(java.util.stream.Collectors.joining(", ")));
            List<PhashIndex.Hit> hits = phashIndex.findWithin(uploadHash, 8, 5);
            if (!hits.isEmpty()) {
                // P3: Batch fetch products thay N+1
                List<Long> pidList = hits.stream().map(h -> h.productId).collect(Collectors.toList());
                Map<Long, ProductResponseDTO> pmap = catalogCache.getProductsByIdsCached(pidList);
                List<ProductResponseDTO> products = new ArrayList<>();
                List<Double> scores = new ArrayList<>();
                for (PhashIndex.Hit h : hits) {
                    ProductResponseDTO p = pmap.get(h.productId);
                    if (p != null) {
                        products.add(p);
                        scores.add(1.0 - (h.distance / 64.0));
                    }
                }
                if (!products.isEmpty()) {
                    // No longer short-circuit. Record the exact match; Stage 1 builds the
                    // "similar" backfill and pinExactFirst() pins this product at #0.
                    pinnedPid = hits.get(0).productId;
                    pinnedDto = pmap.get(pinnedPid);
                    pinnedScore = 1.0 - (hits.get(0).distance / 64.0);
                    System.out.println("✅ pHash exact: pid=" + pinnedPid + " dist=" + hits.get(0).distance
                            + " → pin #0, backfill similars via Stage 1");
                }
            }
        }

        // ========== Stage 1: Gemini Vision context + Hybrid CLIP (RRF + filter + MMR) ==========
        String cleanBase64 = base64Image;
        if (cleanBase64 != null && cleanBase64.contains(",")) {
            cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(',') + 1);
        }
        // P4: Resize ảnh xuống max 512px để giảm payload ML/Gemini → tăng tốc upload + inference
        long _tResize = System.currentTimeMillis();
        int _origLen = cleanBase64 != null ? cleanBase64.length() : 0;
        cleanBase64 = GeminiClient.resizeBase64(cleanBase64, 512);
        int _newLen = cleanBase64 != null ? cleanBase64.length() : 0;
        System.out.println("[IMG-TIMING] resize=" + (System.currentTimeMillis() - _tResize)
                + "ms (base64 " + _origLen + "→" + _newLen + ")");
        final String resizedForVision = cleanBase64;

        // Pre-call Gemini Vision để lấy caption/category/tags/productType. Nếu fail → context empty.
        String parsedCategory = null, parsedProductName = null, parsedColor = null, parsedProductType = null;
        List<String> parsedTags = new ArrayList<>();

        // P2: Cache Vision parse theo uploadHash (pHash). Same image perceptually → reuse parsed JSON.
        String visionCacheKey = uploadHash != null ? "ai:vision:phash:" + uploadHash : null;
        JsonNode cachedVision = null;
        if (visionCacheKey != null) {
            try {
                String cached = redisService.getData(visionCacheKey);
                if (cached != null) {
                    cachedVision = mapper.readTree(cached);
                    System.out.println("[IMG-TIMING] Gemini Vision parse=0ms (CACHE HIT)");
                }
            } catch (Exception ignored) {}
        }

        // P1: Parallel — Vision parse + ML CLIP retrieve.
        long _tParallel = System.currentTimeMillis();

        CompletableFuture<JsonNode> visionFuture;
        if (cachedVision != null) {
            JsonNode finalCached = cachedVision;
            visionFuture = CompletableFuture.completedFuture(finalCached);
        } else {
            visionFuture = CompletableFuture.supplyAsync(() -> {
                long _tV = System.currentTimeMillis();
                try {
                    JsonNode v = geminiClient.visionJson(IMAGE_ANALYSIS_SYSTEM_PROMPT, "Phân tích ảnh này và trích xuất Tags.", resizedForVision);
                    System.out.println("[IMG-TIMING] Gemini Vision parse=" + (System.currentTimeMillis() - _tV) + "ms");
                    return v;
                } catch (Exception e) {
                    System.err.println("⚠️ Gemini Vision pre-parse fail: " + e.getMessage());
                    return null;
                }
            });
        }

        UUID resolvedMlUid = catalogCache.resolveCurrentUserId(sessionId);
        String mlUserId = resolvedMlUid != null ? resolvedMlUid.toString() : null;
        MLRetrieveByImageRequest mlReqEarly = MLRetrieveByImageRequest.builder()
                .imageBase64(cleanBase64)
                .topK(20)
                .retrieveK(40)
                .itemType("PRODUCT")
                .userId(mlUserId)
                .build();
        CompletableFuture<MLRetrieveResponse> mlFuture = CompletableFuture.supplyAsync(() -> {
            long _tM = System.currentTimeMillis();
            try {
                MLRetrieveResponse r = mlService.retrieveByImageFull(mlReqEarly);
                System.out.println("[IMG-TIMING] ML CLIP retrieve=" + (System.currentTimeMillis() - _tM) + "ms");
                return r;
            } catch (Exception e) {
                System.err.println("⚠️ ML retrieve fail: " + e.getMessage());
                return null;
            }
        });

        JsonNode visionJson = null;
        MLRetrieveResponse mlResEarly = null;
        try {
            CompletableFuture.allOf(visionFuture, mlFuture).get(25, TimeUnit.SECONDS);
            visionJson = visionFuture.getNow(null);
            mlResEarly = mlFuture.getNow(null);
        } catch (Exception e) {
            System.err.println("⚠️ Parallel future timeout/error: " + e.getMessage());
            visionJson = visionFuture.getNow(null);
            mlResEarly = mlFuture.getNow(null);
        }

        if (visionJson != null) {
            try {
                List<String> rawTags = mapper.convertValue(visionJson.path("tags"),
                        new TypeReference<List<String>>(){});
                parsedTags = AiTextUtil.sanitizeTags(rawTags);
                parsedCategory = visionJson.path("majorCategory").asText("");
                parsedProductName = visionJson.path("productName").asText("");
                parsedColor = visionJson.path("color").asText("");
                parsedProductType = visionJson.path("productType").asText("");

                if (cachedVision == null && visionCacheKey != null) {
                    try {
                        redisService.saveDataWithTTL(visionCacheKey, mapper.writeValueAsString(visionJson),
                                7, TimeUnit.DAYS);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                System.err.println("⚠️ Vision json convert fail: " + e.getMessage());
            }
        }
        final MLRetrieveResponse mlResultEarly = mlResEarly;
        System.out.println("[IMG-TIMING] Parallel phase total=" + (System.currentTimeMillis() - _tParallel) + "ms");

        try {
            List<Long> typeIds = parsedProductType != null
                    ? categoryService.expandByProductType(parsedProductType)
                    : new ArrayList<>();
            List<Long> superIds = parsedCategory != null
                    ? categoryService.expandToSuperCategoryDescendants(parsedCategory)
                    : new ArrayList<>();
            List<Long> categoryIds = !typeIds.isEmpty() ? typeIds : superIds;
            System.out.println("🔍 Gemini parsed: category='" + parsedCategory
                    + "' productType='" + parsedProductType
                    + "' productName='" + parsedProductName + "' tags=" + parsedTags
                    + " → typeIds=" + typeIds.size() + " superIds=" + superIds.size()
                    + " → using=" + categoryIds.size() + " " + categoryIds);
            String caption = String.join(" ",
                    parsedCategory != null ? parsedCategory : "",
                    parsedProductName != null ? parsedProductName : "",
                    parsedColor != null ? parsedColor : "",
                    String.join(" ", parsedTags)
            ).trim();

            MLRetrieveResponse mlRes = mlResultEarly;
            List<MLItemResult> clipHits = mlRes != null ? mlRes.getResults() : null;
            if (clipHits != null && !clipHits.isEmpty()) {
                System.out.println("🔍 Hybrid CLIP top scores: " + clipHits.stream()
                        .map(h -> h.getItemId() + "=" + String.format("%.4f", h.getScore() != null ? h.getScore() : 0.0))
                        .collect(Collectors.joining(", "))
                        + " | categoryIds=" + categoryIds.size()
                        + " | ml_latency=" + (mlRes.getLatencyMs() != null ? mlRes.getLatencyMs() + "ms" : "n/a"));
                final double THRESHOLD = (mlRes.getRrfThreshold() != null) ? mlRes.getRrfThreshold() : 0.005;
                List<Long> pidOrder = new ArrayList<>();
                List<Double> scoreOrder = new ArrayList<>();
                for (MLItemResult h : clipHits) {
                    if (h.getItemId() == null) continue;
                    if (h.getScore() == null || h.getScore() < THRESHOLD) continue;
                    try {
                        pidOrder.add(Long.parseLong(h.getItemId()));
                        scoreOrder.add(h.getScore());
                    } catch (NumberFormatException ignored) {}
                }
                long _tDb = System.currentTimeMillis();
                Map<Long, ProductResponseDTO> pmap = catalogCache.getProductsByIdsCached(pidOrder);
                System.out.println("[IMG-TIMING] catalogCache.getProductsByIdsCached(" + pidOrder.size() + ")="
                        + (System.currentTimeMillis() - _tDb) + "ms");
                List<ProductResponseDTO> products = new ArrayList<>();
                List<Double> scores = new ArrayList<>();
                for (int i = 0; i < pidOrder.size(); i++) {
                    ProductResponseDTO p = pmap.get(pidOrder.get(i));
                    if (p != null) {
                        products.add(p);
                        scores.add(scoreOrder.get(i));
                    }
                }
                if (!products.isEmpty()) {
                    Double topClipCosine = clipHits.get(0).getClipScore();
                    System.out.println("✅ Hybrid CLIP hit: " + products.size()
                            + " product(s), topRRF=" + (scores.isEmpty() ? "-" : scores.get(0))
                            + " topCLIP=" + topClipCosine);

                    boolean tooLowConf = topClipCosine != null && topClipCosine < 0.65 && products.size() <= 2;
                    if (tooLowConf) {
                        System.out.println("⚠️ CLIP low-confidence (cosine=" + topClipCosine
                                + ", size=" + products.size() + ") → skip to Stage 2");
                        // fall through
                    } else {
                        boolean clipExact = topClipCosine != null && topClipCosine >= 0.90;
                        if (pinnedPid == null && clipExact && !products.isEmpty()) {
                            pinnedPid = products.get(0).getId();
                            pinnedDto = products.get(0);
                        }

                        String normType = AiProductMatch.normalizeAiProductType(parsedProductType);
                        if (normType != null) {
                            AiProductMatch.StrictTypeFilterResult gated = AiProductMatch.strictTypeFilter(products, scores, normType, categoryService);
                            if (!gated.products().isEmpty()) {
                                products = new ArrayList<>(gated.products());
                                scores = new ArrayList<>(gated.scores());
                                System.out.println("🔎 type gate '" + normType + "' → " + products.size() + " same-type candidates");
                            } else {
                                System.out.println("⚠️ type gate '" + normType + "' empty → keep unfiltered pool");
                            }
                        }

                        String anchorBrand = (pinnedDto != null && pinnedDto.getBrand() != null
                                && !pinnedDto.getBrand().isBlank()) ? pinnedDto.getBrand() : null;
                        String queryBrand = anchorBrand != null
                                ? anchorBrand
                                : AiProductMatch.detectQueryBrand(catalogCache.getProductsForAiAssistantCached(resolvedMlUid), parsedProductName, parsedTags, caption);
                        System.out.println("🏷️ image queryBrand='" + queryBrand + "' (source="
                                + (anchorBrand != null ? "anchor pid=" + pinnedPid : "gemini-text") + ")");

                        if (queryBrand != null && !queryBrand.isBlank() && normType != null) {
                            Set<Long> present = new HashSet<>();
                            for (ProductResponseDTO p : products) {
                                if (p != null && p.getId() != null) present.add(p.getId());
                            }
                            int added = 0;
                            for (ProductResponseDTO p : catalogCache.getProductsForAiAssistantCached(resolvedMlUid)) {
                                if (p == null || p.getId() == null || present.contains(p.getId())) continue;
                                if (p.getBrand() != null && p.getBrand().equalsIgnoreCase(queryBrand)
                                        && categoryService.productMatchesType(p, normType)) {
                                    products.add(p);
                                    scores.add(0.5);
                                    present.add(p.getId());
                                    added++;
                                }
                            }
                            if (added > 0) {
                                System.out.println("➕ brand backfill: +" + added + " " + queryBrand
                                        + " " + normType + " from catalog (pool=" + products.size() + ")");
                            }
                        }

                        String imgQueryColor = ColorVocab.canonical(parsedColor);
                        attributeRerank(products, scores, queryBrand, imgQueryColor, categoryIds, 3);
                        pinExactFirst(products, scores, pinnedPid, pinnedDto, pinnedScore, 10);
                        boolean hasExact = pinnedPid != null;
                        boolean lowConf  = topClipCosine == null || topClipCosine < 0.70;

                        if (sessionId != null) sessionContextStore.setLastProduct(sessionId, products.get(0));
                        result.message = hasExact
                                ? String.format("Tôi nhận ra ảnh này là %s. Đây là sản phẩm khớp, kèm vài gợi ý tương tự:",
                                                products.get(0).getName())
                                : lowConf
                                  ? "Tôi không chắc về sản phẩm trong ảnh, nhưng đây là một số sản phẩm có thể phù hợp:"
                                  : "Tôi tìm thấy một số sản phẩm tương tự với ảnh bạn gửi:";
                        result.data.put("action", "image_search_result");
                        result.data.put("products", products);
                        result.data.put("retrievalScores", scores);
                        result.data.put("extractedTags", parsedTags);
                        if (hasExact) result.data.put("exactProductId", pinnedPid);
                        result.data.put("retrievalSource", hasExact ? "CLIP_HYBRID_PINNED" : "CLIP_HYBRID");
                        _logTotal.run();
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Hybrid CLIP image retrieve fallback: " + e.getMessage());
        }

        // ========== Stage 2 Fallback: reuse Gemini-parsed data + vector/tag DB search ==========
        try {
            List<String> cleanTags = parsedTags.isEmpty() ? parsedTags : AiTextUtil.sanitizeTags(parsedTags);
            List<String> expandedTags = expandColorTags(cleanTags);

            String fallbackCategory = (parsedCategory != null && !parsedCategory.isEmpty()) ? parsedCategory : "ALL";
            String fallbackProductName = (parsedProductName != null && !parsedProductName.isEmpty()) ? parsedProductName : "Sản phẩm";
            String fallbackColor = (parsedColor != null) ? parsedColor : "";

            String description = String.format("%s %s %s", fallbackCategory, fallbackProductName, String.join(" ", cleanTags));

            List<Map.Entry<ProductResponseDTO, Double>> scoredResults = productService.findProductsByVectorWithScores(description);
            List<ProductResponseDTO> finalResults = scoredResults.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            List<Double> retrievalScores = scoredResults.stream().map(Map.Entry::getValue).collect(Collectors.toList());

            if (finalResults.isEmpty()) {
                finalResults = productService.findProductsByImage(cleanTags, fallbackCategory);
                retrievalScores = Collections.nCopies(finalResults.size(), 0.0);
            }

            List<ProductResponseDTO> outProducts = new ArrayList<>(finalResults);
            List<Double> outScores = new ArrayList<>(retrievalScores);
            String fbType = AiProductMatch.normalizeAiProductType(parsedProductType);
            if (fbType != null) {
                AiProductMatch.StrictTypeFilterResult fbGate = AiProductMatch.strictTypeFilter(outProducts, outScores, fbType, categoryService);
                if (!fbGate.products().isEmpty()) {
                    outProducts = new ArrayList<>(fbGate.products());
                    outScores = new ArrayList<>(fbGate.scores());
                    System.out.println("🔎 [fallback] type gate '" + fbType + "' → " + outProducts.size() + " same-type");
                }
            }
            pinExactFirst(outProducts, outScores, pinnedPid, pinnedDto, pinnedScore, 10);
            boolean hasExactFb = pinnedPid != null;

            if (!outProducts.isEmpty()) {
                if (sessionId != null) {
                    sessionContextStore.setLastProduct(sessionId, outProducts.get(0));
                    System.out.println("✅ Image Search Fallback: Saved Context for Session " + sessionId + " -> " + outProducts.get(0).getName());
                }

                result.message = hasExactFb
                        ? String.format("Tôi nhận ra ảnh này là %s. Đây là sản phẩm khớp, kèm vài gợi ý tương tự:", outProducts.get(0).getName())
                        : String.format("Dựa trên hình ảnh %s (%s), tôi tìm thấy %d sản phẩm tương tự:",
                                fallbackProductName, fallbackColor, outProducts.size());
                result.data.put("action", "image_search_result");
                result.data.put("products", outProducts);
                result.data.put("extractedTags", cleanTags);
                result.data.put("retrievalScores", outScores);
                if (hasExactFb) result.data.put("exactProductId", pinnedPid);
                result.data.put("retrievalSource", hasExactFb ? "TAG_FALLBACK_PINNED" : "TAG_FALLBACK");
            } else {
                result.message = String.format("Tôi nhận diện được đây là %s màu %s. Tuy nhiên, hiện tại cửa hàng không có sản phẩm nào khớp.", fallbackProductName, fallbackColor);
                result.data.put("extractedTags", expandedTags);
                result.data.put("products", new ArrayList<>());
                result.data.put("action", "image_search_result");
                result.data.put("retrievalSource", "TAG_FALLBACK");
            }

        } catch (Exception e) {
            e.printStackTrace();
            result.message = "Lỗi khi xử lý ảnh: " + e.getMessage();
        }

        try {
            String userId = null;
            if (sessionId != null) {
                UUID uid = catalogCache.resolveCurrentUserId(sessionId);
                if (uid != null) userId = uid.toString();
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("image_search_action", "process_image");
            metadata.put("aiResponseText", result.message);
            metadata.put("modelVersion", MODEL_VERSION);

            if (result.data != null) {
                metadata.put("extracted_tags", result.data.get("extractedTags"));
                if (result.data.get("products") instanceof List) {
                    List<?> products = (List<?>) result.data.get("products");
                    metadata.put("result_count", products.size());
                    List<Long> retrievedIds = products.stream()
                            .filter(p -> p instanceof ProductResponseDTO)
                            .map(p -> ((ProductResponseDTO) p).getId())
                            .collect(Collectors.toList());
                    metadata.put("retrievedItemIds", retrievedIds);
                    List<String> top5Names = products.stream()
                            .limit(5)
                            .filter(p -> p instanceof ProductResponseDTO)
                            .map(p -> ((ProductResponseDTO) p).getName())
                            .collect(Collectors.toList());
                    metadata.put("top_5_results", top5Names);
                }
                if (result.data.get("retrievalScores") instanceof List) {
                    metadata.put("retrievalScore", result.data.get("retrievalScores"));
                }
            }

            logPublisherService.publishEvent(
                    userId, sessionId,
                    "CHAT_IMAGE_SEARCH",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_IMAGE_SEARCH: " + e.getMessage());
        }
        _logTotal.run();
        return result;
    }

    /** Pin in-shop exact product at #0, dedup, cap at maxSize. Mutates lists in place. (package-private để test) */
    void pinExactFirst(List<ProductResponseDTO> products, List<Double> scores,
                               Long exactPid, ProductResponseDTO exactDto,
                               double exactScore, int maxSize) {
        if (exactPid == null || products == null) return;
        int found = -1;
        for (int i = 0; i < products.size(); i++) {
            ProductResponseDTO p = products.get(i);
            if (p != null && exactPid.equals(p.getId())) { found = i; break; }
        }
        if (found == 0) return;
        if (found > 0) {
            ProductResponseDTO p = products.remove(found);
            Double s = found < scores.size() ? scores.remove(found) : exactScore;
            products.add(0, p);
            scores.add(0, s);
        } else if (exactDto != null) {
            products.add(0, exactDto);
            scores.add(0, exactScore);
        } else {
            return;
        }
        while (products.size() > maxSize) products.remove(products.size() - 1);
        while (scores.size() > maxSize)   scores.remove(scores.size() - 1);
    }

    /** Soft attribute boost (cat/color/brand) + per-brand cap. Run BEFORE pinExactFirst. Mutates in place. */
    private void attributeRerank(List<ProductResponseDTO> products, List<Double> scores,
                                 String queryBrand, String queryColor,
                                 List<Long> categoryIds, int brandCap) {
        int n = products.size();
        if (n <= 1) return;
        final double W_CAT = 0.10, W_COLOR = 0.20, W_BRAND = 0.15;

        Set<Long> catSet = (categoryIds != null) ? new HashSet<>(categoryIds) : Collections.emptySet();
        String qBrand = (queryBrand != null && !queryBrand.isBlank()) ? queryBrand.toLowerCase() : null;
        String qColor = (queryColor != null && !queryColor.isBlank()) ? queryColor : null;

        double[] base = new double[n];
        double[] boosted = new double[n];
        boolean[] isQueryBrand = new boolean[n];
        int[] colorRank = new int[n];
        for (int i = 0; i < n; i++) {
            ProductResponseDTO p = products.get(i);
            base[i] = (i < scores.size() && scores.get(i) != null) ? scores.get(i) : 0.0;
            double add = 0.0;
            if (p != null) {
                if (!catSet.isEmpty() && p.getCategoryId() != null && catSet.contains(p.getCategoryId())) {
                    add += W_CAT;
                }
                if (qColor != null) {
                    int cr = colorRankOf(p, qColor);
                    colorRank[i] = cr;
                    if (cr == 2) add += W_COLOR;
                    else if (cr == 1) add += W_COLOR * 0.6;
                }
                if (qBrand != null && p.getBrand() != null && p.getBrand().toLowerCase().equals(qBrand)) {
                    add += W_BRAND;
                    isQueryBrand[i] = true;
                }
            }
            boosted[i] = base[i] + add;
        }

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> {
            if (isQueryBrand[a] != isQueryBrand[b]) return isQueryBrand[a] ? -1 : 1;
            if (colorRank[a] != colorRank[b]) return Integer.compare(colorRank[b], colorRank[a]);
            return Double.compare(boosted[b], boosted[a]);
        });

        List<ProductResponseDTO> keepP = new ArrayList<>(n), overP = new ArrayList<>();
        List<Double> keepS = new ArrayList<>(n), overS = new ArrayList<>();
        Map<String, Integer> brandCount = new HashMap<>();
        for (int oi : order) {
            ProductResponseDTO p = products.get(oi);
            String b = (p != null && p.getBrand() != null) ? p.getBrand().toLowerCase() : "";
            int c = brandCount.getOrDefault(b, 0);
            if (b.isEmpty() || isQueryBrand[oi] || c < brandCap) {
                keepP.add(p); keepS.add(base[oi]);
                if (!b.isEmpty()) brandCount.put(b, c + 1);
            } else {
                overP.add(p); overS.add(base[oi]);
            }
        }
        keepP.addAll(overP); keepS.addAll(overS);

        // Brand-cap (overflow nối cuối) có thể đẩy sp ĐÚNG MÀU xuống sau sp khác màu.
        // Khi query có màu rõ → sort ỔN ĐỊNH lại theo tầng màu: mọi sp đúng dominant color (cr=2)
        // trước cr=1, rồi mới khác màu (cr=0). Giữ nguyên thứ tự brand-cap/điểm trong cùng tầng.
        if (qColor != null) {
            final String fq = qColor;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < keepP.size(); i++) idx.add(i);
            idx.sort((a, b) -> Integer.compare(colorRankOf(keepP.get(b), fq), colorRankOf(keepP.get(a), fq)));
            List<ProductResponseDTO> rp = new ArrayList<>(keepP.size());
            List<Double> rs = new ArrayList<>(keepP.size());
            for (int i : idx) { rp.add(keepP.get(i)); rs.add(keepS.get(i)); }
            keepP = rp; keepS = rs;
        }

        products.clear(); products.addAll(keepP);
        scores.clear();   scores.addAll(keepS);
    }

    /** Mức khớp màu canonical: 2 = dominant, 1 = trong colors/fallback tag, 0 = không. */
    private int colorRankOf(ProductResponseDTO p, String qColor) {
        if (p == null || qColor == null) return 0;
        int r = p.colorRank(qColor);
        if (r > 0) return r;
        boolean unseeded = (p.getDominantColor() == null || p.getDominantColor().isBlank())
                && (p.getColors() == null || p.getColors().isEmpty());
        if (unseeded && ColorVocab.textMatchesColor(buildAttrHaystack(p), qColor)) {
            return 1;
        }
        return 0;
    }

    /** Lowercased name + tags, for whole-token color matching. */
    private String buildAttrHaystack(ProductResponseDTO p) {
        StringBuilder sb = new StringBuilder();
        if (p.getName() != null) sb.append(' ').append(p.getName());
        if (p.getTags() != null) {
            for (String t : p.getTags()) {
                if (t != null) sb.append(' ').append(t);
            }
        }
        return sb.toString().toLowerCase();
    }

    /** Thêm màu canonical VN tương ứng cho mỗi tag màu (không nới chéo). (package-private để test) */
    List<String> expandColorTags(List<String> tags) {
        List<String> out = new ArrayList<>(tags);
        for (String tag : tags) {
            String canon = ColorVocab.canonical(tag);
            if (canon != null) out.add(canon);
        }
        return out.stream().distinct().collect(Collectors.toList());
    }
}
