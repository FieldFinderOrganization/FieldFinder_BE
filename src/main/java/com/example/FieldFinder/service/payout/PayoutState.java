package com.example.FieldFinder.service.payout;

/** Trạng thái payout chuẩn hóa, độc lập nhà cung cấp (PayOS/mock). */
public enum PayoutState {
    /** Đã nhận / đang xử lý — poll tiếp. */
    PROCESSING,
    /** Tiền đã về TK nhận. */
    SUCCEEDED,
    /** Thất bại vĩnh viễn (sai TK, bị hủy, bị đảo). */
    FAILED,
    /** Không xác định được (lỗi tạm thời khi gọi API) — thử lại sau. */
    UNKNOWN
}
