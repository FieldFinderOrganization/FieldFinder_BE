package com.example.FieldFinder.Enum;

public enum PointTxType {
    EARN_ORDER,      // cộng điểm khi đơn DELIVERED
    REVERT_ORDER,    // trừ lại điểm khi đơn đã DELIVERED bị admin hủy
    REDEEM_VOUCHER   // trừ điểm khi đổi voucher
}
