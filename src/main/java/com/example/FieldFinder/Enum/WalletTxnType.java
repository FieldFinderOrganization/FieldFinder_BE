package com.example.FieldFinder.Enum;

/** Loại giao dịch trong ví chủ sân. Amount dương = cộng (provider nhận), âm = trừ. */
public enum WalletTxnType {
    BOOKING_REVENUE,    // + doanh thu đơn đã đá xong (đã trừ hoa hồng)
    CASH_COMMISSION,    // − hoa hồng nền tảng cho đơn CASH (chủ sân đã cầm tiền tay, trừ ngược vào ví; ví có thể âm)
    CANCEL_PENALTY,     // − phạt khi chủ sân hủy đơn (phần vượt giá gốc)
    HOST_COMPENSATION,  // + bồi thường khi khách hủy sát giờ
    WITHDRAWAL,         // − rút tiền về tài khoản ngân hàng
    TOPUP,              // + chủ sân tự nạp tiền vào ví qua PayOS (xác nhận server-side)
    ADJUSTMENT          // ± điều chỉnh thủ công (admin)
}
