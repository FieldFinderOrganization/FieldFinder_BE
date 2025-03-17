package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.ProviderAddressService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProviderAddressServiceImpl implements ProviderAddressService {
    private final ProviderAddressRepository addressRepository;
    private final ProviderRepository providerRepository;

    public ProviderAddressServiceImpl(ProviderAddressRepository addressRepository, ProviderRepository providerRepository) {
        this.addressRepository = addressRepository;
        this.providerRepository = providerRepository;
    }

    @Override
    public ProviderAddressResponseDTO addAddress(ProviderAddressRequestDTO addressRequestDTO) {
        Provider provider = providerRepository.findById(addressRequestDTO.getProviderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

        ProviderAddress address = new ProviderAddress();
        address.setAddress(addressRequestDTO.getAddress());
        address.setProvider(provider);

        address = addressRepository.save(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public ProviderAddressResponseDTO updateAddress(Long addressId, ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        address.setAddress(addressRequestDTO.getAddress());
        address = addressRepository.save(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public void deleteAddress(Long addressId) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
        addressRepository.delete(address);
    }

    @Override
    public List<ProviderAddressResponseDTO> getAddressesByProvider(Long providerId) {
        return addressRepository.findByProvider_ProviderId(providerId).stream()
                .map(ProviderAddressResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }
}
