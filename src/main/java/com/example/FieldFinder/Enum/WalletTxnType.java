package com.example.FieldFinder.Enum;

/** Loại giao dịch trong ví chủ sân. Amount dương = cộng (provider nhận), âm = trừ. */
public enum WalletTxnType {
    BOOKING_REVENUE,    // + doanh thu đơn đã đá xong (đã trừ hoa hồng)
    CANCEL_PENALTY,     // − phạt khi chủ sân hủy đơn (phần vượt giá gốc)
    HOST_COMPENSATION,  // + bồi thường khi khách hủy sát giờ
    WITHDRAWAL,         // − rút tiền về tài khoản ngân hàng
    ADJUSTMENT          // ± điều chỉnh thủ công (admin)
}
