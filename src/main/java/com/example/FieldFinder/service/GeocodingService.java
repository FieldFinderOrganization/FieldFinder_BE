package com.example.FieldFinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class GeocodingService {

    public record LatLng(double latitude, double longitude) {}

    private final WebClient webClient;
    private final String userAgent;

    public GeocodingService(WebClient webClient,
                            @Value("${geocoding.user-agent:FieldFinder/1.0 (admin@fieldfinder.local)}") String userAgent) {
        this.webClient = webClient;
        this.userAgent = userAgent;
    }

    public Optional<LatLng> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        try {
            JsonNode result = webClient.get()
                    .uri("https://nominatim.openstreetmap.org/search?q={q}&format=json&limit=1", address)
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
}
