package com.example.FieldFinder.service.payout;

/**
 * Kết quả một lần gọi payout / poll trạng thái.
 *
 * @param payoutId      id lệnh chi phía nhà cung cấp (để poll), null nếu tạo lỗi
 * @param referenceId   referenceId ta gửi lên
 * @param state         trạng thái chuẩn hóa
 * @param providerState chuỗi trạng thái gốc của nhà cung cấp (lưu để truy vết)
 * @param toAccountName tên chủ TK nhà cung cấp xác nhận (dùng để verify TK)
 * @param message       thông điệp lỗi / mô tả, null nếu OK
 */
public record PayoutResult(
        String payoutId,
        String referenceId,
        PayoutState state,
        String providerState,
        String toAccountName,
        String message
) {
}
