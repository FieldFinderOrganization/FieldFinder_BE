package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.PointInfoResponseDTO;
import com.example.FieldFinder.entity.Order;

import java.util.UUID;

public interface PointService {

    /** Cộng điểm khi đơn DELIVERED (10.000đ = 1 điểm). Idempotent theo orderId. */
    void awardForOrder(Order order);

    /** Trừ lại điểm khi đơn đã DELIVERED bị hủy. No-op nếu chưa từng cộng. */
    void revertForOrder(Long orderId);

    PointInfoResponseDTO getPointInfo(UUID userId);

    /** Đổi điểm lấy voucher; trả về số dư mới. */
    int redeem(UUID userId, UUID discountId);
}
