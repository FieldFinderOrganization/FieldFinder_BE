package com.example.FieldFinder.Enum;

/** Trạng thái giao dịch ví. Ghi sổ nội bộ = COMPLETED; rút tiền đi qua vòng đời PayOS. */
public enum WalletTxnStatus {
    COMPLETED,    // ghi sổ tức thì (doanh thu/phạt/bồi thường/điều chỉnh)
    PENDING,      // rút: chờ đẩy lệnh chi
    PROCESSING,   // rút: đã gửi PayOS, chờ kết quả
    SUCCEEDED,    // rút: chi thành công
    FAILED        // rút: thất bại (đã hoàn lại số dư ví)
}
