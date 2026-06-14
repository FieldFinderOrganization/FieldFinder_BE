package com.example.FieldFinder.repository;


import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {
    List<Review> findByPitch_PitchId(UUID pitchId);

    // Chỉ lấy đánh giá đã duyệt để hiển thị công khai.
    List<Review> findByPitch_PitchIdAndStatus(UUID pitchId, ReviewStatus status);

    List<Review> findByUser_UserId(UUID userId);

    // Hàng đợi kiểm duyệt cho admin (PENDING / REJECTED / APPROVED).
    List<Review> findByStatusOrderByCreatedAtDesc(ReviewStatus status);

    long countByStatus(ReviewStatus status);

    Optional<Review> findByPitch_PitchIdAndUser_UserId(UUID pitchId, UUID userId);

    // Điểm trung bình chỉ tính trên đánh giá đã duyệt.
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.pitch.pitchId = :pitchId " +
            "AND r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED")
    Double findAverageRatingByPitchId(UUID pitchId);

    @Query("SELECT AVG(r.rating) FROM Review r " +
            "WHERE r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED")
    Double findOverallAverageRating();

    // Thống kê rating theo từng sân của 1 provider (chỉ review đã duyệt) —
    // dùng cho bảng xếp hạng "đánh giá cao nhất". Mỗi dòng: [pitchId, avgRating, reviewCount].
    @Query("SELECT r.pitch.pitchId, AVG(r.rating), COUNT(r) FROM Review r " +
            "WHERE r.pitch.providerAddress.provider.providerId = :providerId " +
            "AND r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED " +
            "GROUP BY r.pitch.pitchId")
    List<Object[]> findRatingStatsByProvider(@Param("providerId") UUID providerId);

    @Query("SELECT r.rating, COUNT(r) FROM Review r " +
            "WHERE r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED " +
            "GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> countByRating();

    List<Review> findTop10ByOrderByCreatedAtDesc();

    long countByRatingEquals(int rating);

    // Backfill 1 lần khi khởi động: dữ liệu cũ (status null) -> APPROVED để không bị ẩn.
    @Modifying
    @Transactional
    @Query("UPDATE Review r SET r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED " +
            "WHERE r.status IS NULL")
    int backfillApprovedWhereStatusNull();
}