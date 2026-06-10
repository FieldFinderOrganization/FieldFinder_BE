package com.example.FieldFinder.moderation;

/**
 * Kết quả của bước kiểm duyệt tự động cho một nội dung bình luận.
 *
 * @param rejected true  -> auto từ chối (đưa vào tab "Bị từ chối" cho admin xem lại).
 *                 false -> qua bước auto, chuyển sang PENDING chờ duyệt thủ công.
 * @param reason   lý do từ chối (chỉ có ý nghĩa khi rejected = true).
 */
public record ModerationResult(boolean rejected, String reason) {

    public static ModerationResult pass() {
        return new ModerationResult(false, null);
    }

    public static ModerationResult reject(String reason) {
        return new ModerationResult(true, reason);
    }
}
