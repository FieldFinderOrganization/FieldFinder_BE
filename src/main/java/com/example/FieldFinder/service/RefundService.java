package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;

import java.math.BigDecimal;

public interface RefundService {

    RefundRequest issueRefundCredit(User user,
                                    RefundSourceType sourceType,
                                    String sourceId,
                                    BigDecimal amount,
                                    String reason);

    int DEFAULT_EXPIRY_DAYS = 90;

    String CODE_PREFIX = "RF-";

    Discount generateRefundDiscount(BigDecimal amount);
}
