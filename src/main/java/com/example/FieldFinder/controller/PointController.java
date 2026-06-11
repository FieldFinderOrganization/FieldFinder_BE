package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.PointInfoResponseDTO;
import com.example.FieldFinder.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    /** Số dư điểm + 50 giao dịch gần nhất. */
    @GetMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PointInfoResponseDTO> getPointInfo(@PathVariable UUID userId) {
        return ResponseEntity.ok(pointService.getPointInfo(userId));
    }

    /** Đổi điểm lấy voucher. Body: {"discountId": "..."} */
    @PostMapping("/{userId}/redeem")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> redeem(
            @PathVariable UUID userId,
            @RequestBody Map<String, String> body) {
        UUID discountId;
        try {
            discountId = UUID.fromString(body.getOrDefault("discountId", ""));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        int balance = pointService.redeem(userId, discountId);
        return ResponseEntity.ok(Map.of(
                "message", "Đổi voucher thành công",
                "balance", balance));
    }
}
