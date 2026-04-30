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
                .build();
    }
}