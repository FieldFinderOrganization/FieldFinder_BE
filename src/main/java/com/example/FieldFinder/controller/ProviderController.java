package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.service.ProviderService;
import com.example.FieldFinder.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/providers")
public class ProviderController {
    private final ProviderService providerService;
    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }
    @PostMapping
    public ResponseEntity<ProviderResponseDTO> createProvider(@RequestBody ProviderRequestDTO providerRequestDTO) {
        return ResponseEntity.ok(providerService.createProvider(providerRequestDTO));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderResponseDTO> updateProvider(@PathVariable Long id, @RequestBody ProviderRequestDTO providerRequestDTO) {
        return ResponseEntity.ok(providerService.updateProvider(id, providerRequestDTO));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProvider(@PathVariable Long id) {
        providerService.deleteProvider(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<ProviderResponseDTO>> getAllProviders() {
        return ResponseEntity.ok(providerService.getAllProviders());
    }
}
