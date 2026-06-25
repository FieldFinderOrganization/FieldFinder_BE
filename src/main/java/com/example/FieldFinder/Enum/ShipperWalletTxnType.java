package com.example.FieldFinder.Enum;

/**
 * Loại giao dịch ví shipper. Số dư ví CÓ DẤU: + cộng, − trừ.
 * Đơn DELIVERED luôn cộng phí ship; đơn CASH (COD) trừ thêm tiền hàng shipper thu hộ
 * (số âm = công nợ phải nộp). Rút tiền đi qua vòng đời PayOS như ví chủ sân.
 */
public enum ShipperWalletTxnType {
    SHIP_EARNING,   // + phí ship gốc của đơn đã giao (khách freeship shipper vẫn hưởng)
    COD_COLLECTED,  // − tiền hàng shipper thu hộ trên đơn CASH (công nợ phải nộp lại)
    COD_REMIT,      // + shipper nộp lại tiền hàng thu hộ (giảm công nợ) — Phase 2
    WITHDRAWAL,     // − rút tiền về tài khoản ngân hàng
    ADJUSTMENT      // ± điều chỉnh thủ công (admin / hoàn lệnh rút thất bại)
}
