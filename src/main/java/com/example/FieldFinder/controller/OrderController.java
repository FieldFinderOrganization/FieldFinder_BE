package com.example.FieldFinder.controller;


import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.dto.res.ShipperEarningsDTO;
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

    @GetMapping("/available")
    @PreAuthorize("hasRole('SHIPPER')")
    public List<OrderResponseDTO> getAvailableOrders() {
        return orderService.getAvailableOrdersForShipper();
    }

    @PutMapping("/{id}/claim")
    @PreAuthorize("hasRole('SHIPPER')")
    public ResponseEntity<OrderResponseDTO> claimOrder(@PathVariable Long id,
                                                       Authentication authentication) {
        UUID shipperId = getUserIdFromAuth(authentication);
        if (shipperId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(orderService.claimOrder(id, shipperId));
    }

    @GetMapping("/shipper/me")
    @PreAuthorize("hasRole('SHIPPER')")
    public ResponseEntity<List<OrderResponseDTO>> getMyShipperOrders(Authentication authentication) {
        UUID shipperId = getUserIdFromAuth(authentication);
        if (shipperId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(orderService.getOrdersByShipperId(shipperId));
    }

    /** Thu nhập shipper tính server-side (tổng phí ship gốc đơn DELIVERED) theo hôm nay/tuần/tháng. */
    @GetMapping("/shipper/me/earnings")
    @PreAuthorize("hasRole('SHIPPER')")
    public ResponseEntity<ShipperEarningsDTO> getMyShipperEarnings(Authentication authentication) {
        UUID shipperId = getUserIdFromAuth(authentication);
        if (shipperId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(orderService.getShipperEarnings(shipperId));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrderResponseDTO> cancelOrder(
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false) String reason,
            Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(orderService.cancelOrderByUser(id, userId, reason));
    }
}