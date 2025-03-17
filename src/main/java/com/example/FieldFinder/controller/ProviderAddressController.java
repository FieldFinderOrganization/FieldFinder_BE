package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.service.ProviderAddressService;
import com.example.FieldFinder.service.ProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/provider-addresses")
public class ProviderAddressController {
    private final ProviderAddressService providerAddressService;
    public ProviderAddressController(ProviderAddressService providerAddressService) {
        this.providerAddressService = providerAddressService;
    }
    @PostMapping
    public ResponseEntity<ProviderAddressResponseDTO> addAddress(@RequestBody ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddressResponseDTO response = providerAddressService.addAddress(addressRequestDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<ProviderAddressResponseDTO> updateAddress(@PathVariable Long addressId,
                                                                    @RequestBody ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddressResponseDTO response = providerAddressService.updateAddress(addressId, addressRequestDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long addressId) {
        providerAddressService.deleteAddress(addressId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<List<ProviderAddressResponseDTO>> getAddressesByProvider(@PathVariable Long providerId) {
        List<ProviderAddressResponseDTO> response = providerAddressService.getAddressesByProvider(providerId);
        return ResponseEntity.ok(response);
    }
}
