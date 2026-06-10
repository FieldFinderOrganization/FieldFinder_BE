package com.example.FieldFinder.dto.req;

import lombok.*;

/**
 * Body cho thao tác từ chối đánh giá của admin (lý do tuỳ chọn).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationActionRequestDTO {
    private String reason;
}
