package com.example.FieldFinder.service.payout;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Trừu tượng hóa việc chi tiền ra TK ngân hàng. Khóa luận cắm {@link MockPayoutProvider};
 * production cắm {@link PayOsPayoutProvider}. Chuyển nhà cung cấp = đổi 1 config, không sửa job.
 */
public interface PayoutProvider {

    /**
     * Tạo lệnh chi. PHẢI idempotent theo {@code cmd.referenceId()} — gọi lại cùng referenceId
     * không được chi 2 lần.
     */
    PayoutResult disburse(PayoutCommand cmd);

    /** Poll trạng thái một lệnh chi đã tạo. */
    PayoutResult getStatus(String payoutId);

    /** Số dư TK chi (VND). Rỗng nếu không truy vấn được. */
    Optional<BigDecimal> getBalance();
}
