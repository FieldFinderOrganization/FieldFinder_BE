package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;

import java.util.List;

public interface DiscountService {
    DiscountResponseDTO createDiscount(DiscountRequestDTO dto);
    DiscountResponseDTO updateDiscount(String id, DiscountRequestDTO dto);
    void deleteDiscount(String id);
    List<DiscountResponseDTO> getAllDiscounts();
    DiscountResponseDTO getDiscountById(String id);
}
