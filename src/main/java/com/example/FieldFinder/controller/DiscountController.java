package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.AssignDiscountRequestDTO;
import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.req.DiscountStatusRequestDTO;
import com.example.FieldFinder.dto.req.UserDiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.dto.res.UserDiscountResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.service.DiscountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/discounts")
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountService discountService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<DiscountResponseDTO> create(@RequestBody DiscountRequestDTO dto) {
        return ResponseEntity.ok(discountService.createDiscount(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<DiscountResponseDTO> update(@PathVariable String id, @RequestBody DiscountRequestDTO dto) {
        return ResponseEntity.ok(discountService.updateDiscount(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        discountService.deleteDiscount(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DiscountResponseDTO>> getAll() {
        return ResponseEntity.ok(discountService.getAllDiscounts());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DiscountResponseDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(discountService.getDiscountById(id));
    }

    @PostMapping("/{userId}/save")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> saveDiscountToWallet(
            @PathVariable UUID userId,
            @RequestBody UserDiscountRequestDTO dto) {

        discountService.saveDiscountToWallet(userId, dto);
        return ResponseEntity.ok("Voucher saved successfully to wallet!");
    }

    @GetMapping("/{userId}/wallet")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserDiscountResponseDTO>> getMyWallet(@PathVariable UUID userId) {
        return ResponseEntity.ok(discountService.getMyWallet(userId));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<DiscountResponseDTO> updateStatus(
            @PathVariable String id,
            @RequestBody DiscountStatusRequestDTO dto) {
        Discount.DiscountStatus status;
        try {
            status = Discount.DiscountStatus.valueOf(dto.getStatus());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(discountService.updateStatus(id, status));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<Void> assignToUsers(
            @PathVariable String id,
            @RequestBody AssignDiscountRequestDTO dto) {
        discountService.assignToUsers(id, dto.getUserIds());
        return ResponseEntity.ok().build();
    }
}