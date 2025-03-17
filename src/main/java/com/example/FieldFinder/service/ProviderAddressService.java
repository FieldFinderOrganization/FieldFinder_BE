package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;

import java.util.List;

public interface ProviderAddressService {
    ProviderAddressResponseDTO addAddress(ProviderAddressRequestDTO addressRequestDTO);
    ProviderAddressResponseDTO updateAddress(Long addressId, ProviderAddressRequestDTO addressRequestDTO);
    void deleteAddress(Long addressId);
    List<ProviderAddressResponseDTO> getAddressesByProvider(Long providerId);
}
