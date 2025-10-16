package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.service.ProviderService;
import com.example.FieldFinder.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/providers")
public class ProviderController {
    private final ProviderService providerService;
    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }
    @PostMapping
    public ProviderResponseDTO createProvider(@RequestBody ProviderRequestDTO dto) {
        return providerService.createProvider(dto);
    }

    @PutMapping("/{providerId}")
    public ProviderResponseDTO updateProvider(@PathVariable UUID providerId, @RequestBody ProviderRequestDTO dto) {
        return providerService.updateProvider(providerId, dto);
    }
    @GetMapping("/user/{userId}")
    public ProviderResponseDTO getProviderByUserId(@PathVariable UUID userId) {
        return providerService.getProviderByUserId(userId);
    }
    @GetMapping
    public List<ProviderResponseDTO> getAllProviders() {
        return providerService.getAllProviders();
    }

}

