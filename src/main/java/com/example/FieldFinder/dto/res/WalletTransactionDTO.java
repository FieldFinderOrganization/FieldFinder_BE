package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.WalletTransaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Một dòng sao kê ví. amount CÓ DẤU (+ cộng, − trừ). */
@Data
@Builder
public class WalletTransactionDTO {
    private UUID txnId;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String reason;
    private String status;
    private String maskedAccount;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public static WalletTransactionDTO fromEntity(WalletTransaction t) {
        return WalletTransactionDTO.builder()
                .txnId(t.getTxnId())
                .type(t.getType() != null ? t.getType().name() : null)
                .amount(t.getAmount())
                .balanceAfter(t.getBalanceAfter())
                .reason(t.getReason())
                .status(t.getStatus() != null ? t.getStatus().name() : null)
                .maskedAccount(mask(t.getBankAccountNumber()))
                .createdAt(t.getCreatedAt())
                .processedAt(t.getProcessedAt())
                .build();
    }

    private static String mask(String acc) {
        if (acc == null || acc.length() <= 4) return acc;
        return "*".repeat(acc.length() - 4) + acc.substring(acc.length() - 4);
    }
}
