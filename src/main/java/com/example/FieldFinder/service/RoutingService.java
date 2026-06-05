package com.example.FieldFinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Lấy tuyến đường lái xe (polyline) giữa 2 toạ độ từ OSRM self-host.
 * KHÔNG dùng traffic — ETA tĩnh theo tốc độ giới hạn. Cache Redis né gọi lặp.
 * OSRM down / tắt -> trả Optional.empty (client tự fallback vẽ đường thẳng).
 */
@Slf4j
@Service
public class RoutingService {

    /** Tuyến: geometry = encoded polyline (precision 5), distance (m), duration (s). */
    public record Route(String geometry, double distanceMeters, double durationSeconds) {}

    private static final long CACHE_TTL_SECONDS = 120;

    private final WebClient webClient;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int timeoutMs;
    private final boolean enabled;

    public RoutingService(WebClient webClient,
                          RedisService redisService,
                          ObjectMapper objectMapper,
                          @Value("${osrm.base-url:http://localhost:5000}") String baseUrl,
                          @Value("${osrm.timeout-ms:8000}") int timeoutMs,
                          @Value("${osrm.enabled:true}") boolean enabled) {
        this.webClient = webClient;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.enabled = enabled;
    }

    public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
        if (!enabled) return Optional.empty();

        // Cache theo toạ độ làm tròn 4 chữ số (~11m) để né gọi lặp khi shipper nhích nhẹ.
        String key = String.format(Locale.ROOT, "route:%.4f,%.4f:%.4f,%.4f",
                fromLat, fromLng, toLat, toLng);
        String cached = redisService.getData(key);
        if (cached != null) {
            try {
                return Optional.of(objectMapper.readValue(cached, Route.class));
            } catch (Exception ignored) {
                // cache hỏng -> gọi lại OSRM bên dưới.
            }
        }

        try {
            // OSRM dùng thứ tự lng,lat.
            String uri = String.format(Locale.ROOT,
                    "%s/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=polyline",
                    baseUrl, fromLng, fromLat, toLng, toLat);

            JsonNode resp = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            if (resp == null || !"Ok".equals(resp.path("code").asText())) {
                return Optional.empty();
            }
            JsonNode routes = resp.path("routes");
            if (!routes.isArray() || routes.isEmpty()) return Optional.empty();

            JsonNode r = routes.get(0);
            Route route = new Route(
                    r.path("geometry").asText(),
                    r.path("distance").asDouble(),
                    r.path("duration").asDouble());

            try {
                redisService.saveDataWithTTL(key, objectMapper.writeValueAsString(route),
                        CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Redis lỗi không được làm hỏng phản hồi tuyến.
            }
            return Optional.of(route);
        } catch (Exception e) {
            log.warn("OSRM route failed {},{} -> {},{}: {}",
                    fromLat, fromLng, toLat, toLng, e.getMessage());
            return Optional.empty();
        }
    }
}
