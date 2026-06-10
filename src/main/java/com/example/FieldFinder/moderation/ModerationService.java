package com.example.FieldFinder.moderation;

/**
 * Kiểm duyệt tự động nội dung bình luận trước khi đưa sang kiểm duyệt thủ công.
 */
public interface ModerationService {

    /**
     * @param comment nội dung bình luận (có thể null/blank nếu người dùng chỉ chấm sao).
     * @return kết quả: bị từ chối kèm lý do, hoặc cho qua.
     */
    ModerationResult moderate(String comment);
}
