package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.ProviderService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProviderServiceImpl implements ProviderService {
    private final ProviderRepository providerRepository;

    public ProviderServiceImpl(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Override
    public ProviderResponseDTO createProvider(ProviderRequestDTO providerRequestDTO) {
        if (providerRepository.existsByName(providerRequestDTO.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider already exists.");
        }

        Provider provider = new Provider();
        provider.setName(providerRequestDTO.getName());
        provider.setEmail(providerRequestDTO.getEmail());
        provider.setPhone(providerRequestDTO.getPhone());

        provider = providerRepository.save(provider);
        return ProviderResponseDTO.fromEntity(provider);
    }

    @Override
    public ProviderResponseDTO updateProvider(Long providerId, ProviderRequestDTO providerRequestDTO) {
        Provider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

        provider.setName(providerRequestDTO.getName());
        provider.setEmail(providerRequestDTO.getEmail());
        provider.setPhone(providerRequestDTO.getPhone());

        provider = providerRepository.save(provider);
        return ProviderResponseDTO.fromEntity(provider);
    }

    @Override
    public void deleteProvider(Long providerId) {
        Provider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

        if (!provider.getAddresses().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete provider with addresses.");
        }

        providerRepository.delete(provider);
    }

    @Override
    public List<ProviderResponseDTO> getAllProviders() {
        return providerRepository.findAll().stream()
                .map(ProviderResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }
}
