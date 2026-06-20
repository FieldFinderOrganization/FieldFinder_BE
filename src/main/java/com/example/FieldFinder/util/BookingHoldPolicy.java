package com.example.FieldFinder.util;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Chính sách giữ slot động (Dynamic Hold). Nguồn DUY NHẤT để xác định hạn thanh toán
 * của đơn PENDING — dùng cho cả việc BE tự hủy đơn quá hạn lẫn việc FE hiển thị
 * countdown, tránh hai bên tính lệch nhau.
 *
 * Hold timeout theo khoảng cách từ lúc đặt (createdAt) đến giờ bắt đầu slot sớm nhất
 * (earliestStart):
 *   gap ≥ 12h → 30 phút; gap ≥ 3h → 15 phút; còn lại → 5 phút.
 * Hạn thanh toán = min(createdAt + holdTimeout, earliestStart − 90 phút).
 */
public final class BookingHoldPolicy {

    /** Mốc cứng: mọi đơn online phải thanh toán xong trước giờ đá ít nhất 90 phút. */
    public static final long ABSOLUTE_DEADLINE_MINUTES_BEFORE_START = 90;

    private BookingHoldPolicy() {}

    public static long holdTimeoutMinutes(LocalDateTime createdAt, LocalDateTime earliestStart) {
        long gapHours = ChronoUnit.HOURS.between(createdAt, earliestStart);
        if (gapHours >= 12) return 30;
        if (gapHours >= 3) return 15;
        return 5;
    }

    /** Hạn thanh toán thực tế của đơn PENDING; null nếu thiếu dữ liệu. */
    public static LocalDateTime paymentDeadline(LocalDateTime createdAt, LocalDateTime earliestStart) {
        if (createdAt == null || earliestStart == null) return null;
        LocalDateTime holdDeadline =
                createdAt.plusMinutes(holdTimeoutMinutes(createdAt, earliestStart));
        LocalDateTime absoluteDeadline =
                earliestStart.minusMinutes(ABSOLUTE_DEADLINE_MINUTES_BEFORE_START);
        return holdDeadline.isBefore(absoluteDeadline) ? holdDeadline : absoluteDeadline;
    }
}
