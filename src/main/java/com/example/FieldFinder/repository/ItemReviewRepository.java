package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.entity.Item_Review;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ItemReviewRepository extends JpaRepository<Item_Review, Long> {
    List<Item_Review> findByProduct(Product product);

    // Chỉ lấy đánh giá đã duyệt để hiển thị công khai trên trang sản phẩm.
    List<Item_Review> findByProductAndStatus(Product product, ReviewStatus status);

    List<Item_Review> findByUser(User user);

    // Hàng đợi kiểm duyệt cho admin.
    List<Item_Review> findByStatusOrderByCreatedAtDesc(ReviewStatus status);

    long countByStatus(ReviewStatus status);

    boolean existsByUserAndProduct(User user, Product product);

    // Chỉ chặn đánh giá trùng khi bản trước CHƯA bị từ chối -> cho phép gửi lại sau khi bị từ chối.
    boolean existsByUserAndProductAndStatusNot(User user, Product product, ReviewStatus status);

    // Backfill 1 lần khi khởi động: dữ liệu cũ (status null) -> APPROVED.
    @Modifying
    @Transactional
    @Query("UPDATE Item_Review r SET r.status = com.example.FieldFinder.Enum.ReviewStatus.APPROVED " +
            "WHERE r.status IS NULL")
    int backfillApprovedWhereStatusNull();
}
