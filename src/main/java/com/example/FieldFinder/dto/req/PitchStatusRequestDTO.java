package com.example.FieldFinder.dto.req;

import java.time.LocalDate;

/**
 * Body cho PATCH /api/pitches/{pitchId}/status
 * - Để INACTIVE: cung cấp targetDate (ngày bắt đầu ngưng)
 * - Để ACTIVE:   cung cấp status = "ACTIVE" (targetDate bỏ qua)
 */
public class PitchStatusRequestDTO {
    /** "ACTIVE" hoặc "INACTIVE". Nếu null, mặc định xử lý như INACTIVE cần targetDate. */
    private String status;

    /** Ngày bắt đầu ngưng (chỉ dùng khi status = INACTIVE). */
    private LocalDate targetDate;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
}
