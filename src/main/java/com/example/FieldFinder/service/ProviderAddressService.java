package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ProviderAddressService {
    ProviderAddressResponseDTO addAddress(ProviderAddressRequestDTO addressRequestDTO);
    ProviderAddressResponseDTO updateAddress(UUID addressId, ProviderAddressRequestDTO addressRequestDTO);
    void deleteAddress(UUID addressId);
    List<ProviderAddressResponseDTO> getAddressesByProvider(UUID providerId);
    List<ProviderAddressResponseDTO> getAllAddresses();

}
