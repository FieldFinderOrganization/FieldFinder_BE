package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.ProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProviderServiceImpl implements ProviderService {

    private final ProviderRepository providerRepository;

    @Override
    public ProviderResponseDTO createProvider(ProviderRequestDTO dto) {
        Provider provider = Provider.builder()
                .userId(dto.getUserId())
                .cardNumber(dto.getCardNumber())
                .bank(dto.getBank())
                .build();
        provider = providerRepository.save(provider);
        return mapToDto(provider);
    }

    @Override
    public ProviderResponseDTO updateProvider(UUID providerId, ProviderRequestDTO dto) {
        Provider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        provider.setCardNumber(dto.getCardNumber());
        provider.setBank(dto.getBank());
        provider = providerRepository.save(provider);
        return mapToDto(provider);
    }

    @Override
    public ProviderResponseDTO getProviderByUserId(UUID userId) {
        Provider provider = providerRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Provider not found for userId: " + userId));
        return mapToDto(provider);
    }

    @Override
    public List<ProviderResponseDTO> getAllProviders() {
        return providerRepository.findAll().stream()
                .map(this::mapToDto)
                .toList();
    }


    private ProviderResponseDTO mapToDto(Provider provider) {
        ProviderResponseDTO dto = new ProviderResponseDTO();
        dto.setProviderId(provider.getProviderId());
        dto.setUserId(provider.getUserId());
        dto.setCardNumber(provider.getCardNumber());
        dto.setBank(provider.getBank());
        return dto;
    }
}
