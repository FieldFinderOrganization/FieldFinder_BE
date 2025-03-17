package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;

import java.util.List;

public interface ProviderService {
    ProviderResponseDTO createProvider(ProviderRequestDTO providerRequestDTO);
    ProviderResponseDTO updateProvider(Long providerId, ProviderRequestDTO providerRequestDTO);
    void deleteProvider(Long providerId);
    List<ProviderResponseDTO> getAllProviders();
}
