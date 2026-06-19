package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.RefundRequest;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class RefundRequestResponseDTO {
    private UUID refundId;
    private String sourceType;
    private String sourceId;
    private BigDecimal amount;
    private String status;
    private String reason;
    private String refundCode;
    private LocalDate expiryDate;
    private BigDecimal remainingValue;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    // ----- Hoàn tiền mặt (PayOS payout) -----
    private String refundMethod;     // VOUCHER | CASH
    private String maskedAccount;    // số TK nhận đã che
    private String payosTxnState;    // trạng thái giao dịch PayOS gần nhất
    private LocalDateTime deadlineAt;

    public static RefundRequestResponseDTO fromEntity(RefundRequest req, BigDecimal remainingValue) {
        return RefundRequestResponseDTO.builder()
                .refundId(req.getRefundId())
                .sourceType(req.getSourceType().name())
                .sourceId(req.getSourceId())
                .amount(req.getAmount())
                .status(req.getStatus().name())
                .reason(req.getReason())
                .refundCode(req.getIssuedDiscount() != null ? req.getIssuedDiscount().getCode() : null)
                .expiryDate(req.getIssuedDiscount() != null ? req.getIssuedDiscount().getEndDate() : null)
                .remainingValue(remainingValue)
                .createdAt(req.getCreatedAt())
                .processedAt(req.getProcessedAt())
                .refundMethod(req.getRefundMethod() != null ? req.getRefundMethod().name() : null)
                .maskedAccount(mask(req.getBankAccountNumber()))
                .payosTxnState(req.getPayosTxnState())
                .deadlineAt(req.getDeadlineAt())
                .build();
    }

    private static String mask(String acc) {
        if (acc == null || acc.length() <= 4) return acc;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < acc.length() - 4; i++) sb.append('*');
        return sb.append(acc.substring(acc.length() - 4)).toString();
    }
}