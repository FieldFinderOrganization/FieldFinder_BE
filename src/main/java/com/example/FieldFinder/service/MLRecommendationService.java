package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.MLRecommendCtrRequest;
import com.example.FieldFinder.dto.req.MLRecommendNextRequest;
import com.example.FieldFinder.dto.req.MLRetrieveByImageRequest;
import com.example.FieldFinder.dto.req.MLRetrieveRequest;
import com.example.FieldFinder.dto.res.MLItemResult;
import com.example.FieldFinder.dto.res.MLRecommendCtrResponse;
import com.example.FieldFinder.dto.res.MLRecommendNextResponse;
import com.example.FieldFinder.dto.res.MLRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wrapper gọi FastAPI ML service.
 * Có circuit breaker đơn giản: nếu fail liên tục → tạm tắt 60s, fallback null.
 */
@Slf4j
@Service
public class MLRecommendationService {

    private final WebClient mlWebClient;
    private final boolean enabled;
    private final int timeoutMs;
    private final MlCircuitBreaker circuitBreaker;

    public MLRecommendationService(
            @Qualifier("mlWebClient") WebClient mlWebClient,
            @Value("${ml.api.enabled:true}") boolean enabled,
            @Value("${ml.api.timeout-ms:5000}") int timeoutMs,
            MlCircuitBreaker circuitBreaker) {
        this.mlWebClient = mlWebClient;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        this.circuitBreaker = circuitBreaker;
    }

    private boolean circuitOpen() {
        return !circuitBreaker.allowRequest();
    }

    private void recordFailure(String op, Throwable e) {
        circuitBreaker.recordFailure();
        log.warn("ML API {} failed: {}", op, e.getMessage());
    }

    private void recordSuccess() {
        circuitBreaker.recordSuccess();
    }

    /**
     * SASRec next-K item cho user. Trả null nếu fail / disabled.
     */
    public List<MLItemResult> recommendNext(String userId, int topK, String itemType) {
        if (!enabled || circuitOpen()) {
            return null;
        }
        try {
            MLRecommendNextRequest req = MLRecommendNextRequest.builder()
                    .userId(userId).topK(topK).itemType(itemType)
                    .build();
            MLRecommendNextResponse res = mlWebClient.post()
                    .uri("/recommend/next")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MLRecommendNextResponse.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            recordSuccess();
            return res != null ? res.getResults() : null;
        } catch (Exception e) {
            recordFailure("recommendNext", e);
            return null;
        }
    }

    /**
     * DeepFM CTR rerank. Trả map<itemId, score>. Empty map nếu fail.
     */
    public Map<String, Double> rerankCtr(String userId,
                                         List<String> candidateIds,
                                         List<String> itemTypes,
                                         Map<String, Object> context) {
        if (!enabled || circuitOpen() || candidateIds == null || candidateIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            MLRecommendCtrRequest req = MLRecommendCtrRequest.builder()
                    .userId(userId)
                    .candidateIds(candidateIds)
                    .itemTypes(itemTypes)
                    .context(context != null ? context : new HashMap<>())
                    .build();
            MLRecommendCtrResponse res = mlWebClient.post()
                    .uri("/recommend/ctr")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MLRecommendCtrResponse.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            recordSuccess();
            if (res == null || res.getScores() == null) return Collections.emptyMap();
            Map<String, Double> out = new HashMap<>();
            for (MLRecommendCtrResponse.CtrScore s : res.getScores()) {
                out.put(s.getItemId(), s.getCtrScore() != null ? s.getCtrScore() : 0.0);
            }
            return out;
        } catch (Exception e) {
            recordFailure("rerankCtr", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Personalized RAG retrieve. Trả null nếu fail.
     */
    public List<MLItemResult> retrieve(String query, String userId, int topK, String itemType) {
        if (!enabled || circuitOpen()) {
            return null;
        }
        try {
            MLRetrieveRequest req = MLRetrieveRequest.builder()
                    .query(query)
                    .userId(userId)
                    .topK(topK)
                    .itemType(itemType)
                    .build();
            MLRetrieveResponse res = mlWebClient.post()
                    .uri("/retrieve")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MLRetrieveResponse.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            recordSuccess();
            return res != null ? res.getResults() : null;
        } catch (Exception e) {
            recordFailure("retrieve", e);
            return null;
        }
    }

    public List<MLItemResult> retrieveByImage(String base64Image, int topK, String itemType) {
        return retrieveByImage(MLRetrieveByImageRequest.builder()
                .imageBase64(base64Image)
                .topK(topK)
                .itemType(itemType)
                .build());
    }

    public List<MLItemResult> retrieveByImage(MLRetrieveByImageRequest req) {
        MLRetrieveResponse res = retrieveByImageFull(req);
        return res != null ? res.getResults() : null;
    }

    /**
     * Full response variant — includes latency_ms + rrf_threshold from ML config.
     */
    public MLRetrieveResponse retrieveByImageFull(MLRetrieveByImageRequest req) {
        if (!enabled || circuitOpen()) return null;
        try {
            MLRetrieveResponse res = mlWebClient.post()
                    .uri("/retrieve/image")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MLRetrieveResponse.class)
                    .timeout(Duration.ofMillis(Math.max(timeoutMs, 30_000)))
                    .block();
            recordSuccess();
            return res;
        } catch (Exception e) {
            recordFailure("retrieveByImage", e);
            return null;
        }
    }

    public boolean isHealthy() {
        if (!enabled) return false;
        try {
            return Boolean.TRUE.equals(mlWebClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(2000))
                    .map(m -> "ok".equals(m.get("status")))
                    .blockOptional()
                    .orElse(false));
        } catch (Exception e) {
            return false;
        }
    }
}
