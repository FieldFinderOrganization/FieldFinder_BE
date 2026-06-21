package com.example.FieldFinder.Enum;

public enum RefundSourceType {
    ORDER,
    BOOKING,
    /** Bù tiền cho CHỦ SÂN khi khách hủy sát giờ (payout về TK chủ sân). Tách khỏi BOOKING để không đụng khóa idempotency với khoản hoàn cho khách. */
    BOOKING_HOST,
    /** Giải ngân DOANH THU booking cho chủ sân sau khi trận đá kết thúc (escrow → TK chủ sân). Tách khỏi BOOKING/BOOKING_HOST để idempotency riêng. */
    BOOKING_PAYOUT
}