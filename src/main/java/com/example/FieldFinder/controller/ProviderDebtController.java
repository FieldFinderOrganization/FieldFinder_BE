package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.ProviderDebtResponseDTO;
import com.example.FieldFinder.service.ProviderDebtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Admin quản lý khoản nợ chủ sân (do hệ thống ứng hoàn khi chủ sân hủy). */
@RestController
@RequestMapping("/api/admin/provider-debts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ProviderDebtController {

    private final ProviderDebtService providerDebtService;

    @GetMapping
    public ResponseEntity<List<ProviderDebtResponseDTO>> listOutstanding() {
        List<ProviderDebtResponseDTO> out = providerDebtService.listOutstanding().stream()
                .map(ProviderDebtResponseDTO::from).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/settle")
    public ResponseEntity<ProviderDebtResponseDTO> settle(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderDebtResponseDTO.from(providerDebtService.settle(id)));
    }

    @PostMapping("/{id}/waive")
    public ResponseEntity<ProviderDebtResponseDTO> waive(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderDebtResponseDTO.from(providerDebtService.waive(id)));
    }
}
