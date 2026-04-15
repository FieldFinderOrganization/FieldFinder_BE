package com.example.FieldFinder.controller;


import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.service.OrderService;
import com.example.FieldFinder.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final RedisService redisService;

    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return null;
        }

        try {
            Object principal = authentication.getPrincipal();
            String email = null;

            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String) {
                email = (String) principal;
            }

            if (email != null) {
                return redisService.getUserIdByEmail(email);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public OrderResponseDTO create(@RequestBody OrderRequestDTO request) {
        return orderService.createOrder(request);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<OrderResponseDTO> getAll() {
        return orderService.getAllOrders();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public OrderResponseDTO getById(@PathVariable Long id) {
        return orderService.getOrderById(id);
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<OrderResponseDTO>> getOrdersByUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public OrderResponseDTO updateStatus(@PathVariable Long id, @RequestParam String status) {
        return orderService.updateOrderStatus(id, status);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public void delete(@PathVariable Long id) {
        orderService.deleteOrder(id);
    }

    @PutMapping("/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable Long orderId,
                                                           Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);

        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }

        orderService.cancelOrderByUser(orderId, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hủy đơn đặt sản phẩm thành công!");
        response.put("orderId", orderId);

        return ResponseEntity.ok(response);
    }
}
