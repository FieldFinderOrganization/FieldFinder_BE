package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.dto.res.SuggestedProductsResponseDTO;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.ProductRecommendationService;
import com.example.FieldFinder.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductRecommendationServiceImpl implements ProductRecommendationService {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final MLRecommendationService mlService;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    public ProductRecommendationServiceImpl(ProductRepository productRepository,
                                            UserRepository userRepository,
                                            OrderRepository orderRepository,
                                            ProductService productService,
                                            MLRecommendationService mlService) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.mlService = mlService;
    }

    @Override
    @Transactional(readOnly = true)
    public SuggestedProductsResponseDTO getSuggested(Long productId, UUID userId, int limit) {
        log.info("[SUGGEST-PRODUCT] getSuggested called - productId: {}, userId: {}, limit: {}", productId, userId, limit);
        int safeLimit = Math.max(1, Math.min(limit, 20));

        Product currentProduct = productRepository.findById(productId).orElse(null);
        if (currentProduct == null) {
            log.warn("[SUGGEST-PRODUCT] Product not found for ID: {}", productId);
            return new SuggestedProductsResponseDTO(List.of(), List.of(), List.of());
        }

        // 1. Fetch Similar Products
        Long categoryId = currentProduct.getCategory() != null ? currentProduct.getCategory().getCategoryId() : null;
        List<Product> similarEntities = productRepository.findSimilarProducts(
                productId, categoryId, currentProduct.getBrand(), currentProduct.getSex(), PageRequest.of(0, safeLimit)
        );
        List<Long> similarIds = similarEntities.stream().map(Product::getProductId).toList();
        log.info("[SUGGEST-PRODUCT] Found {} similar product entities", similarIds.size());

        // 2. Fetch Top Selling Products
        List<Product> topSellingEntities = productRepository.findTopSellingProducts(PageRequest.of(0, safeLimit));
        List<Long> topSellingIds = topSellingEntities.stream()
                .map(Product::getProductId)
                .filter(id -> !id.equals(productId))
                .toList();
        log.info("[SUGGEST-PRODUCT] Found {} top selling product entities", topSellingIds.size());

        // 3. Fetch History-based Products (Orders and Views)
        List<Long> historyIds = List.of();
        if (userId != null) {
            historyIds = loadHistoryProductIds(productId, userId, safeLimit, categoryId, currentProduct.getBrand(), currentProduct.getSex());
        }
        log.info("[SUGGEST-PRODUCT] Found {} history-based product IDs", historyIds.size());

        // 4. Batch fetch response DTOs for all gathered IDs to support user specific prices/discounts
        Set<Long> allIds = new LinkedHashSet<>();
        allIds.addAll(similarIds);
        allIds.addAll(topSellingIds);
        allIds.addAll(historyIds);

        if (allIds.isEmpty()) {
            return new SuggestedProductsResponseDTO(List.of(), List.of(), List.of());
        }

        // ML CTR chỉ cần danh sách ID → bắn song song với phần hydrate (getProductsByIds)
        // vốn là bottleneck. Latency ML (≤ timeout) bị "nuốt" dưới thời gian hydrate.
        final UUID uid = userId;
        final Long anchorId = productId;
        long tStart = System.currentTimeMillis();
        CompletableFuture<Map<String, Double>> ctrFuture =
                CompletableFuture.supplyAsync(() -> rerankByCtr(allIds, uid, anchorId));

        Map<Long, ProductResponseDTO> dtosMap = productService.getProductsByIds(new ArrayList<>(allIds), userId);
        long tHydrate = System.currentTimeMillis();
        log.info("[SUGGEST-PRODUCT][TIMING] hydrate getProductsByIds took {} ms", tHydrate - tStart);

        List<ProductResponseDTO> similarDtos = similarIds.stream()
                .map(dtosMap::get)
                .filter(Objects::nonNull)
                .toList();

        List<ProductResponseDTO> topSellingDtos = topSellingIds.stream()
                .map(dtosMap::get)
                .filter(Objects::nonNull)
                .toList();

        List<ProductResponseDTO> historyDtos = historyIds.stream()
                .map(dtosMap::get)
                .filter(Objects::nonNull)
                .toList();

        // Lấy kết quả ML (đã chạy nền). Lỗi/timeout → map rỗng → giữ thứ tự heuristic.
        Map<String, Double> ctrScores = joinCtr(ctrFuture);
        log.info("[SUGGEST-PRODUCT][TIMING] joinCtr waited {} ms (total after hydrate)", System.currentTimeMillis() - tHydrate);
        similarDtos = applyCtrOrder(similarDtos, ctrScores);
        topSellingDtos = applyCtrOrder(topSellingDtos, ctrScores);
        historyDtos = applyCtrOrder(historyDtos, ctrScores);

        log.info("[SUGGEST-PRODUCT] Returning response: similar={}, topSelling={}, historyBased={}, ctrReranked={}",
                similarDtos.size(), topSellingDtos.size(), historyDtos.size(), !ctrScores.isEmpty());

        return new SuggestedProductsResponseDTO(similarDtos, topSellingDtos, historyDtos);
    }

    /** Chờ kết quả ML nền; lỗi/timeout → map rỗng (giữ thứ tự heuristic). */
    private Map<String, Double> joinCtr(CompletableFuture<Map<String, Double>> future) {
        try {
            // Cap phần CHỜ THÊM sau hydrate. ML đã chạy nền suốt lúc hydrate rồi.
            // Ngắn để ML down/treo không kéo response (circuit breaker sẽ tự mở sau vài fail).
            return future.get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[SUGGEST-PRODUCT] CTR future not ready, fallback heuristic: {}", e.getMessage());
            future.cancel(true);
            return Collections.emptyMap();
        }
    }

    /**
     * Gọi ML DeepFM CTR cho tập ứng viên. Trả map<productId(String), ctrScore>.
     * Map rỗng nếu ML tắt / circuit open / lỗi (đã xử lý trong MLRecommendationService).
     */
    private Map<String, Double> rerankByCtr(Set<Long> candidateIds, UUID userId, Long anchorProductId) {
        if (candidateIds == null || candidateIds.isEmpty()) return Collections.emptyMap();
        List<String> candIds = candidateIds.stream().map(String::valueOf).toList();
        List<String> itemTypes = candIds.stream().map(x -> "PRODUCT").toList();
        Map<String, Object> context = new HashMap<>();
        context.put("source", "suggested-product");
        if (anchorProductId != null) context.put("anchor_product_id", anchorProductId);
        try {
            Map<String, Double> scores = mlService.rerankCtr(
                    userId != null ? userId.toString() : null,
                    candIds, itemTypes, context);
            log.info("[SUGGEST-PRODUCT] CTR rerank scored {} / {} candidates",
                    scores != null ? scores.size() : 0, candIds.size());
            return scores != null ? scores : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("[SUGGEST-PRODUCT] CTR rerank failed, fallback heuristic: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Sắp lại 1 nhóm theo điểm CTR giảm dần (ổn định). Score thiếu coi như 0.
     * Map rỗng → giữ nguyên thứ tự gốc.
     */
    private List<ProductResponseDTO> applyCtrOrder(List<ProductResponseDTO> items,
                                                   Map<String, Double> ctrScores) {
        if (ctrScores.isEmpty() || items.size() < 2) return items;
        return items.stream()
                .sorted(Comparator.comparingDouble(
                        (ProductResponseDTO d) ->
                                ctrScores.getOrDefault(String.valueOf(d.getId()), 0.0))
                        .reversed())
                .collect(Collectors.toList());
    }

    private List<Long> loadHistoryProductIds(Long excludeId, UUID userId, int limit,
                                             Long categoryId, String brand, String sex) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();

        // A. Load from view logs in MongoDB
        if (mongoTemplate != null) {
            try {
                Query q = Query.query(
                        Criteria.where("userId").is(userId.toString())
                                .and("eventType").is("VIEW_PRODUCT")
                                .and("itemType").is("PRODUCT")
                ).with(Sort.by(Sort.Direction.DESC, "timestamp"));
                q.limit(50);
                List<InteractionLog> logs = mongoTemplate.find(q, InteractionLog.class);
                log.info("[SUGGEST-PRODUCT-HISTORY] Found {} view logs in Mongo for user {}", logs.size(), userId);
                for (InteractionLog l : logs) {
                    if (l.getItemId() == null) continue;
                    try {
                        Long pid = Long.parseLong(l.getItemId());
                        if (!pid.equals(excludeId)) ids.add(pid);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception e) {
                log.warn("[SUGGEST-PRODUCT-HISTORY] MongoDB query failed: {}", e.getMessage(), e);
            }
        } else {
            log.info("[SUGGEST-PRODUCT-HISTORY] mongoTemplate is null, skipping view history");
        }

        // B. Load from order history in PostgreSQL
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                List<Order> orders = orderRepository.findByUser(user);
                log.info("[SUGGEST-PRODUCT-HISTORY] Found {} orders for user {}", orders.size(), userId);
                for (Order order : orders) {
                    if (order.getItems() == null) continue;
                    for (var item : order.getItems()) {
                        Product p = item.getProduct();
                        if (p != null && !p.getProductId().equals(excludeId)) {
                            ids.add(p.getProductId());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SUGGEST-PRODUCT-HISTORY] Order history query failed: {}", e.getMessage(), e);
        }

        if (ids.isEmpty()) return List.of();

        // Sort history product IDs: prioritize products of same category, brand, or sex
        List<Product> loadedProducts = productRepository.findAllListViewByIds(ids);
        
        List<Long> sortedIds = loadedProducts.stream()
                .sorted((p1, p2) -> {
                    int score1 = 0;
                    int score2 = 0;
                    if (p1.getCategory() != null && p1.getCategory().getCategoryId().equals(categoryId)) score1 += 3;
                    if (p1.getBrand() != null && p1.getBrand().equalsIgnoreCase(brand)) score1 += 2;
                    if (p1.getSex() != null && p1.getSex().equalsIgnoreCase(sex)) score1 += 1;

                    if (p2.getCategory() != null && p2.getCategory().getCategoryId().equals(categoryId)) score2 += 3;
                    if (p2.getBrand() != null && p2.getBrand().equalsIgnoreCase(brand)) score2 += 2;
                    if (p2.getSex() != null && p2.getSex().equalsIgnoreCase(sex)) score2 += 1;

                    return Integer.compare(score2, score1);
                })
                .map(Product::getProductId)
                .limit(limit)
                .collect(Collectors.toList());

        log.info("[SUGGEST-PRODUCT-HISTORY] Ordered history based IDs: {}", sortedIds);
        return sortedIds;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getSuggestedFootballProducts(UUID userId, int limit) {
        log.info("[SUGGEST-FOOTBALL-PRODUCTS] Called - userId: {}, limit: {}", userId, limit);
        int safeLimit = Math.max(1, Math.min(limit, 20));

        List<Product> footballEntities = productRepository.findFootballProducts(PageRequest.of(0, safeLimit));
        List<Long> ids = footballEntities.stream().map(Product::getProductId).toList();

        if (ids.isEmpty()) {
            return List.of();
        }

        // ML CTR rerank song song với hydrate (giống getSuggested).
        final UUID uid = userId;
        Set<Long> candidateIds = new LinkedHashSet<>(ids);
        CompletableFuture<Map<String, Double>> ctrFuture =
                CompletableFuture.supplyAsync(() -> rerankByCtr(candidateIds, uid, null));

        Map<Long, ProductResponseDTO> dtosMap = productService.getProductsByIds(new ArrayList<>(ids), userId);
        List<ProductResponseDTO> result = ids.stream()
                .map(dtosMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, Double> ctrScores = joinCtr(ctrFuture);
        result = applyCtrOrder(result, ctrScores);
        log.info("[SUGGEST-FOOTBALL-PRODUCTS] Returning {} products, ctrReranked={}",
                result.size(), !ctrScores.isEmpty());
        return result;
    }
}
