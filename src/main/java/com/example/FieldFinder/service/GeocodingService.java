package com.example.FieldFinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class GeocodingService {

    public record LatLng(double latitude, double longitude) {}

    private final WebClient webClient;
    private final String userAgent;

    public GeocodingService(WebClient webClient,
                            @Value("${geocoding.user-agent:SportsHub/1.0 (admin@fieldfinder.local)}") String userAgent) {
        this.webClient = webClient;
        this.userAgent = userAgent;
    }

    public Optional<LatLng> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        try {
            // countrycodes=vn: chặn match nước ngoài (vd "Tân Bình" lọt sang Vân Nam TQ).
            // accept-language=vi: ưu tiên kết quả tiếng Việt. Phân biệt trùng tên trong VN
            // (vd "Phước Ngãi" Vĩnh Long vs Quảng Ngãi) PHẢI dựa vào tỉnh trong chuỗi address.
            String q = address.trim();
            JsonNode result = webClient.get()
                    .uri("https://nominatim.openstreetmap.org/search?q={q}&format=json&limit=1&countrycodes=vn&accept-language=vi", q)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();
            if (result == null || !result.isArray() || result.isEmpty()) return Optional.empty();
            JsonNode first = result.get(0);
            double lat = first.path("lat").asDouble();
            double lng = first.path("lon").asDouble();
            if (lat == 0 && lng == 0) return Optional.empty();
            return Optional.of(new LatLng(lat, lng));
        } catch (Exception e) {
            log.warn("Geocode failed for '{}': {}", address, e.getMessage());
            return Optional.empty();
        }
    }

    @Async
    public CompletableFuture<Optional<LatLng>> geocodeAsync(String address) {
        return CompletableFuture.completedFuture(geocode(address));
    }

    /**
     * Reverse-geocode coordinates to a city name suitable for weather lookup.
     * Tries city → town → state → province from Nominatim address components.
     */
    public Optional<String> reverseGeocodeCity(double lat, double lng) {
        try {
            JsonNode root = webClient.get()
                    .uri("https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=json&accept-language=vi",
                            lat, lng)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();
            if (root == null) return Optional.empty();

            JsonNode address = root.path("address");
            for (String key : List.of("city", "town", "municipality", "county", "state", "province")) {
                String value = address.path(key).asText(null);
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
            String display = root.path("display_name").asText(null);
            if (display != null && !display.isBlank()) {
                String first = display.split(",")[0].trim();
                if (!first.isBlank()) return Optional.of(first);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Reverse geocode failed for ({}, {}): {}", lat, lng, e.getMessage());
            return Optional.empty();
        }
    }
}
