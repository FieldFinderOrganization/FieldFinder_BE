package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RefundService {

    RefundRequest issueRefundCredit(User user,
                                    RefundSourceType sourceType,
                                    String sourceId,
                                    BigDecimal amount,
                                    String reason);

    /**
     * Như bản 5 tham số, nhưng mã phát hành bị giới hạn: chỉ dùng đặt sân
     * của provider {@code restrictProviderId} (null = không giới hạn).
     */
    RefundRequest issueRefundCredit(User user,
                                    RefundSourceType sourceType,
                                    String sourceId,
                                    BigDecimal amount,
                                    String reason,
                                    UUID restrictProviderId);

    int DEFAULT_EXPIRY_DAYS = 90;

    String CODE_PREFIX = "RF-";

    Discount generateRefundDiscount(BigDecimal amount);

    Optional<RefundRequest> findBySource(RefundSourceType type, String sourceId);
}