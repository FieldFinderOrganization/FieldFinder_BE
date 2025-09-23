package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.service.DiscountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements DiscountService {

    private final DiscountRepository discountRepository;

    @Override
    public DiscountResponseDTO createDiscount(DiscountRequestDTO dto) {
        Discount discount = dto.toEntity();
        Discount saved = discountRepository.save(discount);
        return DiscountResponseDTO.fromEntity(saved);
    }

    @Override
    public DiscountResponseDTO updateDiscount(String id, DiscountRequestDTO dto) {
        Discount discount = discountRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new RuntimeException("Discount not found"));

        // Cập nhật thủ công các trường (vì toEntity tạo mới chứ không update)
        discount.setCode(dto.getCode());
        discount.setDescription(dto.getDescription());
        discount.setPercentage(dto.getPercentage());
        discount.setStartDate(dto.getStartDate());
        discount.setEndDate(dto.getEndDate());
        discount.setStatus(dto.isActive()
                ? Discount.DiscountStatus.ACTIVE
                : Discount.DiscountStatus.INACTIVE);

        Discount updated = discountRepository.save(discount);
        return DiscountResponseDTO.fromEntity(updated);
    }

    @Override
    public void deleteDiscount(String id) {
        discountRepository.deleteById(UUID.fromString(id));
    }

    @Override
    public List<DiscountResponseDTO> getAllDiscounts() {
        return discountRepository.findAll().stream()
                .map(DiscountResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public DiscountResponseDTO getDiscountById(String id) {
        return discountRepository.findById(UUID.fromString(id))
                .map(DiscountResponseDTO::fromEntity)
                .orElseThrow(() -> new RuntimeException("Discount not found"));
    }
}
