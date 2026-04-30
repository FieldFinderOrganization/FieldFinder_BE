package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.dto.res.RefundRequestResponseDTO;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;
    private final UserDiscountRepository userDiscountRepository;

    @GetMapping("/by-source")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RefundRequestResponseDTO> getBySource(
            @RequestParam("type") String type,
            @RequestParam("id") String sourceId) {
        RefundSourceType sourceType;
        try {
            sourceType = RefundSourceType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Optional<RefundRequest> opt = refundService.findBySource(sourceType, sourceId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        RefundRequest req = opt.get();
        BigDecimal remaining = null;
        if (req.getIssuedDiscount() != null && req.getUser() != null) {
            Optional<UserDiscount> ud = userDiscountRepository.findByUserAndDiscount(
                    req.getUser(), req.getIssuedDiscount());
            if (ud.isPresent()) {
                remaining = ud.get().getRemainingValue();
            }
        }

        return ResponseEntity.ok(RefundRequestResponseDTO.fromEntity(req, remaining));
    }
}