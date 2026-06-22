package com.example.FieldFinder.util;

import java.util.Map;

/**
 * Đổi chuỗi {@code cancelReason} (do FE gửi dạng "KEY" hoặc "KEY:freeText")
 * sang nhãn tiếng Việt để hiển thị trong thông báo / email.
 *
 * <p>Bảng KEY phải khớp với {@code CancelReasonSheet} bên FE (Flutter).
 */
public final class CancelReasonLabels {

    private CancelReasonLabels() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
            // Hủy đơn sản phẩm
            Map.entry("CHANGED_MIND", "Đổi ý không muốn mua nữa"),
            Map.entry("WRONG_ITEM", "Đặt nhầm sản phẩm / kích cỡ"),
            Map.entry("FOUND_BETTER_PRICE", "Tìm được giá tốt hơn ở nơi khác"),
            Map.entry("DELIVERY_TOO_SLOW", "Thời gian giao hàng quá lâu"),
            // Hủy đặt sân (khách)
            Map.entry("SCHEDULE_CONFLICT", "Bận đột xuất, không sắp xếp được"),
            Map.entry("WRONG_TIME_PITCH", "Đặt nhầm sân / khung giờ"),
            Map.entry("WEATHER", "Thời tiết xấu"),
            Map.entry("FOUND_BETTER_PITCH", "Tìm được sân khác phù hợp hơn"),
            // Hủy đặt sân (chủ sân)
            Map.entry("PITCH_MAINTENANCE", "Sân bảo trì / gặp sự cố"),
            Map.entry("CANNOT_SERVE", "Không thể phục vụ khung giờ này"),
            // Chung
            Map.entry("OTHER", "Lý do khác")
    );

    /**
     * Trả về nhãn tiếng Việt cho lý do hủy.
     * <ul>
     *   <li>KEY đã biết → nhãn tiếng Việt.</li>
     *   <li>"OTHER:nội dung" → chính nội dung người dùng nhập.</li>
     *   <li>Không khớp KEY (vd lý do hệ thống tự sinh "Sân tạm ngưng...") → giữ nguyên.</li>
     * </ul>
     */
    public static String vi(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return value;
        int idx = value.indexOf(':');
        String key = idx >= 0 ? value.substring(0, idx) : value;
        String rest = idx >= 0 ? value.substring(idx + 1).trim() : "";
        String label = LABELS.get(key);
        if (label == null) return value; // text tiếng Việt sẵn / không xác định
        if ("OTHER".equals(key)) return rest.isEmpty() ? label : rest;
        return rest.isEmpty() ? label : label + " (" + rest + ")";
    }
}
