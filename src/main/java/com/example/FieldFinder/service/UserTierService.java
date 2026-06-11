package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.TierInfoResponseDTO;

import java.util.UUID;

public interface UserTierService {

    /**
     * Tính lại hạng cho 1 user từ tổng chi tiêu 12 tháng gần nhất.
     * Lên hạng: tự gán voucher hạng mới + gửi email chúc mừng (sau commit).
     * Gọi sau mỗi lần status đơn hàng đổi sang/khỏi nhóm đã-thanh-toán.
     */
    void recalcTier(UUID userId);

    /** Thông tin hạng + tiến độ lên hạng kế tiếp cho FE. */
    TierInfoResponseDTO getTierInfo(UUID userId);
}
