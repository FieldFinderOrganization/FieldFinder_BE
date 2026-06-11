package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.req.UserDiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.dto.res.UserDiscountResponseDTO;
import com.example.FieldFinder.entity.Discount;

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
    DiscountResponseDTO updateStatus(String id, Discount.DiscountStatus status);
    void assignToUsers(String id, List<UUID> userIds);

    /** Gán 1 mã cho mọi user thuộc hạng :tier trở lên (set-based). */
    int assignToTier(String id, com.example.FieldFinder.Enum.UserTier tier);

    /**
     * Gán cho user mới đăng ký mọi mã PROMOTION đang ACTIVE còn hạn mà hạng MEMBER dùng được —
     * fix lỗ hổng acc tạo sau ngày phát hành mã không thấy mã trong ví.
     */
    void grantWelcomeVouchers(UUID userId);
}