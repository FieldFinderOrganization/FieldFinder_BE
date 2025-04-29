package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ProviderService {
    ProviderResponseDTO createProvider(ProviderRequestDTO dto);
    ProviderResponseDTO updateProvider(UUID providerId, ProviderRequestDTO dto);
    ProviderResponseDTO getProviderByUserId(UUID userId);
    List<ProviderResponseDTO> getAllProviders();

}
