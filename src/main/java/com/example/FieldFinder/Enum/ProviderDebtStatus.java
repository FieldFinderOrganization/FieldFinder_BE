package com.example.FieldFinder.Enum;

/** Trạng thái khoản nợ chủ sân (do hệ thống ứng tiền hoàn khi chủ sân hủy). */
public enum ProviderDebtStatus {
    /** Chưa thu hồi — sẽ trừ vào doanh thu kỳ sau. */
    OUTSTANDING,
    /** Đã thu hồi (trừ doanh thu / chủ sân nộp lại). */
    SETTLED,
    /** Miễn (admin quyết). */
    WAIVED
}
