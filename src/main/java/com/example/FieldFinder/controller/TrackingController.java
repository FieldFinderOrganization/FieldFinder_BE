package com.example.FieldFinder.controller;

import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.RoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real-time tracking vị trí shipper.
 * - Live: broadcast qua STOMP, KHÔNG ghi DB (giống chat).
 * - Vị trí cuối: lưu Redis key "tracking:{orderId}" TTL 60s để mở app giữa chừng thấy ngay.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class TrackingController {

    private static final long LOCATION_TTL_SECONDS = 60;

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final RoutingService routingService;

    /** Shipper gửi toạ độ ~10s/lần tới /app/tracking. */
    @MessageMapping("/tracking")
    public void processLocation(@Payload Map<String, Object> payload) {
        Object orderIdObj = payload.get("orderId");
        if (orderIdObj == null) return;
        String orderId = String.valueOf(orderIdObj);

        // Dùng ts server (đồng nhất, tránh lệch đồng hồ thiết bị) làm mốc nội suy.
        // speed/bearing pass-through từ shipper để client xoay icon + nội suy mượt.
        Map<String, Object> location = new HashMap<>();
        location.put("orderId", orderId);
        location.put("lat", payload.get("lat"));
        location.put("lng", payload.get("lng"));
        location.put("speed", payload.get("speed"));
        location.put("bearing", payload.get("bearing"));
        location.put("ts", System.currentTimeMillis());

        // Broadcast live cho user/admin đang xem đơn này.
        messagingTemplate.convertAndSend("/topic/tracking." + orderId, location);

        // Lưu vị trí cuối vào Redis (tự xoá sau TTL). Không đụng DB.
        try {
            redisService.saveDataWithTTL(
                    "tracking:" + orderId,
                    objectMapper.writeValueAsString(location),
                    LOCATION_TTL_SECONDS,
                    TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis lỗi không được làm hỏng luồng broadcast.
        }
    }

    /** User/Admin mở app giữa chừng: lấy vị trí cuối trong 60s gần nhất. */
    @GetMapping("/{id}/last-location")
    public ResponseEntity<?> getLastLocation(@PathVariable Long id) {
        String data = redisService.getData("tracking:" + id);
        if (data == null) {
            return ResponseEntity.noContent().build();
        }
        try {
            return ResponseEntity.ok(objectMapper.readValue(data, Map.class));
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    /**
     * Tuyến đường shipper→đích để client vẽ polyline + snap marker bám đường.
     * from = vị trí shipper hiện tại, to = đích giao. 204 nếu OSRM tắt/lỗi
     * (client tự fallback nội suy đường thẳng).
     */
    @GetMapping("/{id}/route")
    public ResponseEntity<?> getRoute(@PathVariable Long id,
                                      @RequestParam double fromLat,
                                      @RequestParam double fromLng,
                                      @RequestParam double toLat,
                                      @RequestParam double toLng) {
        return routingService.route(fromLat, fromLng, toLat, toLng)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
