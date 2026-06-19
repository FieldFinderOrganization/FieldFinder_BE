package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.ProviderDebt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Khoản nợ chủ sân — dùng cho dashboard admin. */
public record ProviderDebtResponseDTO(
        UUID providerDebtId,
        UUID providerId,
        String providerName,
        String sourceBookingId,
        BigDecimal amount,
        String status,
        String reason,
        boolean overdue,
        LocalDateTime deadlineAt,
        LocalDateTime createdAt,
        LocalDateTime settledAt
) {
    public static ProviderDebtResponseDTO from(ProviderDebt d) {
        boolean overdue = d.getDeadlineAt() != null
                && d.getStatus() == com.example.FieldFinder.Enum.ProviderDebtStatus.OUTSTANDING
                && d.getDeadlineAt().isBefore(LocalDateTime.now());
        UUID providerId = d.getProvider() != null ? d.getProvider().getProviderId() : null;
        String providerName = null;
        try {
            if (d.getProvider() != null && d.getProvider().getUser() != null) {
                providerName = d.getProvider().getUser().getName();
            }
        } catch (Exception ignored) {}
        return new ProviderDebtResponseDTO(
                d.getProviderDebtId(), providerId, providerName, d.getSourceBookingId(),
                d.getAmount(), d.getStatus().name(), d.getReason(), overdue,
                d.getDeadlineAt(), d.getCreatedAt(), d.getSettledAt());
    }
}
