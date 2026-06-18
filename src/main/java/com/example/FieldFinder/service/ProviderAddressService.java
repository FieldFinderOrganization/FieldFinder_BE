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

    /** Danh sách khu vực (address) phân biệt — cho dropdown khi provider thêm địa chỉ (auto-gom theo tên). */
    List<String> getDistinctAreas();

    /** Geocode + lưu toạ độ cho mọi địa chỉ đang thiếu lat/lng. Trả số bản ghi cập nhật thành công. */
    int backfillMissingCoordinates();

}
