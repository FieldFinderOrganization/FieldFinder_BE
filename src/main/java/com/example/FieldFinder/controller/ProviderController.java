package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.service.ProviderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ProviderResponseDTO createProvider(@RequestBody ProviderRequestDTO dto) {
        return providerService.createProvider(dto);
    }

    @PutMapping("/{providerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ProviderResponseDTO updateProvider(@PathVariable UUID providerId, @RequestBody ProviderRequestDTO dto) {
        return providerService.updateProvider(providerId, dto);
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ProviderResponseDTO getProviderByUserId(@PathVariable UUID userId) {
        return providerService.getProviderByUserId(userId);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProviderResponseDTO> getAllProviders() {
        return providerService.getAllProviders();
    }

}
