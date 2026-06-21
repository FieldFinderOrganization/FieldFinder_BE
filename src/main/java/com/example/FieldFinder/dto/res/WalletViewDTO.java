package com.example.FieldFinder.dto.res;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Tổng quan ví chủ sân cho FE. */
@Data
@Builder
public class WalletViewDTO {
    private BigDecimal balance;       // số dư (có thể âm = nợ)
    private BigDecimal reserve;       // phần giữ lại (đệm phạt hủy)
    private BigDecimal withdrawable;  // rút được = max(0, balance − reserve)
    private BigDecimal minWithdraw;   // sàn rút tối thiểu mỗi lệnh
    private boolean blocked;          // ví âm quá hạn ⇒ bị chặn nhận booking
    private LocalDateTime negativeSince;
    private long blockGraceDays;      // FE tính hạn = negativeSince + grace
}
