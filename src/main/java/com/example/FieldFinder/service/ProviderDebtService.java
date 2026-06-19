package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.ProviderDebt;

import java.util.List;
import java.util.UUID;

public interface ProviderDebtService {

    /** Tất cả khoản nợ chưa trả (admin xem). */
    List<ProviderDebt> listOutstanding();

    /** Đánh dấu đã thu hồi. */
    ProviderDebt settle(UUID providerDebtId);

    /** Miễn nợ (admin quyết). */
    ProviderDebt waive(UUID providerDebtId);

    /** Chủ sân này có đang bị chặn nhận booking (có nợ quá hạn) không. */
    boolean isBookingBlocked(UUID providerId);
}
