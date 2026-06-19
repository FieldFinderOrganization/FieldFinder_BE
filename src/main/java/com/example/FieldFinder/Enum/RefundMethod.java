package com.example.FieldFinder.Enum;

/** Cách hoàn tiền cho một RefundRequest. */
public enum RefundMethod {
    /** Phát mã giảm giá REFUND_CREDIT (luồng cũ — fallback khi user chưa có TK ngân hàng). */
    VOUCHER,
    /** Chuyển tiền mặt về tài khoản ngân hàng user đã đăng ký, qua PayOS payout. */
    CASH
}
