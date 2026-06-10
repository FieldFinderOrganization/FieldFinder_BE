package com.example.FieldFinder.Enum;

/**
 * Trạng thái kiểm duyệt của một đánh giá (sân hoặc sản phẩm).
 *
 * Luồng: tạo mới -> kiểm duyệt tự động.
 *  - Nếu auto chặn  -> REJECTED (moderationSource = AUTO).
 *  - Nếu auto cho qua -> PENDING (chờ admin duyệt thủ công).
 * Admin duyệt thủ công -> APPROVED hoặc REJECTED (moderationSource = MANUAL).
 * Chỉ APPROVED mới hiển thị công khai và được tính vào điểm trung bình.
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
