package com.example.FieldFinder.Enum;

public enum RefundStatus {
    // ----- Luồng voucher (cũ) -----
    REQUESTED,
    ISSUED,
    REJECTED,
    FAILED,

    // ----- Luồng hoàn tiền mặt PayOS payout -----
    /** Đã tạo, chờ job đẩy lệnh chi. */
    PAYOUT_PENDING,
    /** Đã gửi lệnh chi PayOS, chờ ngân hàng xử lý (poll trạng thái). */
    PAYOUT_PROCESSING,
    /** Tiền đã về TK user (PayOS state SUCCEEDED). */
    PAYOUT_SUCCEEDED,
    /** Chi thất bại sau khi hết số lần thử — cần admin xử lý. */
    PAYOUT_FAILED
}
