package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.req.UserDiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.dto.res.UserDiscountResponseDTO;

import java.util.List;
import java.util.UUID;

public interface DiscountService {
    DiscountResponseDTO createDiscount(DiscountRequestDTO dto);
    DiscountResponseDTO updateDiscount(String id, DiscountRequestDTO dto);
    void deleteDiscount(String id);
    List<DiscountResponseDTO> getAllDiscounts();
    DiscountResponseDTO getDiscountById(String id);
    void saveDiscountToWallet(UUID userId, UserDiscountRequestDTO dto);
    List<UserDiscountResponseDTO> getMyWallet(UUID userId);
}
