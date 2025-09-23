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
import java.util.UUID;

@CrossOrigin("*")
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
    public ResponseEntity<ProviderAddressResponseDTO> updateAddress(@PathVariable UUID addressId,
                                                                    @RequestBody ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddressResponseDTO response = providerAddressService.updateAddress(addressId, addressRequestDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> deleteAddress(@PathVariable UUID addressId) {
        providerAddressService.deleteAddress(addressId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<List<ProviderAddressResponseDTO>> getAddressesByProvider(@PathVariable UUID providerId) {
        List<ProviderAddressResponseDTO> response = providerAddressService.getAddressesByProvider(providerId);
        return ResponseEntity.ok(response);
    }
    @GetMapping
    public ResponseEntity<List<ProviderAddressResponseDTO>> getAllProviderAddresses() {
        List<ProviderAddressResponseDTO> response = providerAddressService.getAllAddresses();
        return ResponseEntity.ok(response);
    }

}
