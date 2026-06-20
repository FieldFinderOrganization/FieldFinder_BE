package com.example.FieldFinder.service.banklookup;

/**
 * Tra cứu tên chủ tài khoản ngân hàng để xác thực số TK là THẬT
 * (NAPAS qua aggregator, vd VietQR.io). Dùng cho cả provider và user —
 * mọi TK nhận hoàn tiền đều phải xác thực trước khi chi.
 */
public interface BankLookupService {

    /**
     * @param bankBin       mã BIN ngân hàng (vd "970415")
     * @param accountNumber số tài khoản (6–19 số)
     */
    BankLookupResult lookup(String bankBin, String accountNumber);

    /**
     * Kết quả tra cứu.
     * <ul>
     *   <li>{@code ok=true} ⇒ TK thật, {@code accountName} là tên chuẩn ngân hàng trả về.</li>
     *   <li>{@code ok=false, transientError=false} ⇒ TK ảo / sai BIN — nên từ chối.</li>
     *   <li>{@code ok=false, transientError=true} ⇒ lỗi tạm thời (429/timeout/mạng) — nên soft-save.</li>
     * </ul>
     */
    record BankLookupResult(boolean ok, boolean transientError, String accountName, String message) {

        public static BankLookupResult ok(String accountName) {
            return new BankLookupResult(true, false, accountName, null);
        }

        /** TK không hợp lệ — kết luận chắc chắn từ ngân hàng. */
        public static BankLookupResult invalid(String message) {
            return new BankLookupResult(false, false, null, message);
        }

        /** Không tra cứu được vì lỗi tạm thời — chưa kết luận TK thật/ảo. */
        public static BankLookupResult unavailable(String message) {
            return new BankLookupResult(false, true, null, message);
        }
    }
}
