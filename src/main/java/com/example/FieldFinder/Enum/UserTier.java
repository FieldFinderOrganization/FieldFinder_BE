package com.example.FieldFinder.Enum;

/**
 * Hạng thành viên theo tổng chi tiêu 12 tháng gần nhất (VND).
 * Thứ tự khai báo = thứ tự hạng tăng dần — so sánh "hạng trở lên" bằng ordinal().
 */
public enum UserTier {
    MEMBER(0L),
    VIP(2_000_000L),
    GOLD(5_000_000L),
    DIAMOND(10_000_000L);

    private final long threshold;

    UserTier(long threshold) {
        this.threshold = threshold;
    }

    public long getThreshold() {
        return threshold;
    }

    /** Tier cao nhất có threshold <= spending. */
    public static UserTier fromSpending(double spending) {
        UserTier result = MEMBER;
        for (UserTier tier : values()) {
            if (spending >= tier.threshold) {
                result = tier;
            }
        }
        return result;
    }

    /** Hạng kế tiếp, null nếu đã DIAMOND. */
    public UserTier next() {
        int idx = ordinal() + 1;
        return idx < values().length ? values()[idx] : null;
    }

    public boolean isAtLeast(UserTier other) {
        return other == null || this.ordinal() >= other.ordinal();
    }
}
