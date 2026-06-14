package com.example.FieldFinder.controller;

import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Thống kê dành cho provider. Mirror {@link AdminStatisticsController} nhưng giới hạn
 * theo provider. Quyền role-based giống các endpoint /api/bookings/provider/{id} hiện có
 * (không kiểm tra ownership ở mức path để khớp pattern sẵn có).
 */
@RestController
@RequestMapping("/api/providers/{providerId}/statistics")
@PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
public class ProviderStatisticsController {

    private final PitchRepository pitchRepository;
    private final BookingRepository bookingRepository;
    private final ReviewRepository reviewRepository;

    public ProviderStatisticsController(PitchRepository pitchRepository,
                                        BookingRepository bookingRepository,
                                        ReviewRepository reviewRepository) {
        this.pitchRepository = pitchRepository;
        this.bookingRepository = bookingRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Mỗi sân của provider kèm số liệu xếp hạng:
     * {pitchId, pitchName, imageUrl, bookingCount, totalRevenue, avgRating, reviewCount}.
     * Trả cả sân chưa có lượt đặt / chưa có đánh giá (giá trị 0) để mọi sân đều lên bảng.
     * Client tự sắp xếp theo từng bảng (đặt nhiều / đánh giá cao / doanh thu cao).
     */
    @GetMapping("/pitch-rankings")
    public ResponseEntity<List<Map<String, Object>>> getPitchRankings(@PathVariable UUID providerId) {
        // 1) Seed từ toàn bộ sân của provider
        Map<UUID, Map<String, Object>> byPitch = new LinkedHashMap<>();
        for (Pitch p : pitchRepository.findByProviderAddress_Provider_ProviderId(providerId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pitchId", p.getPitchId().toString());
            row.put("pitchName", p.getName());
            row.put("imageUrl", (p.getImageUrls() != null && !p.getImageUrls().isEmpty())
                    ? p.getImageUrls().get(0) : null);
            row.put("bookingCount", 0L);
            row.put("totalRevenue", BigDecimal.ZERO);
            row.put("avgRating", BigDecimal.ZERO);
            row.put("reviewCount", 0L);
            byPitch.put(p.getPitchId(), row);
        }

        // 2) Merge lượt đặt + doanh thu
        for (Object[] r : bookingRepository.findPitchBookingStatsByProvider(providerId)) {
            UUID pitchId = (UUID) r[0];
            Map<String, Object> row = byPitch.get(pitchId);
            if (row == null) continue; // sân đã bị xoá nhưng còn booking cũ
            row.put("bookingCount", ((Number) r[1]).longValue());
            row.put("totalRevenue", r[2] != null ? new BigDecimal(r[2].toString()) : BigDecimal.ZERO);
        }

        // 3) Merge rating
        for (Object[] r : reviewRepository.findRatingStatsByProvider(providerId)) {
            UUID pitchId = (UUID) r[0];
            Map<String, Object> row = byPitch.get(pitchId);
            if (row == null) continue;
            double avg = r[1] != null ? ((Number) r[1]).doubleValue() : 0.0;
            row.put("avgRating", BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP));
            row.put("reviewCount", ((Number) r[2]).longValue());
        }

        return ResponseEntity.ok(new ArrayList<>(byPitch.values()));
    }
}
