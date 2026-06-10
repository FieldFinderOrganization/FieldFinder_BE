package com.example.FieldFinder.config;

import com.example.FieldFinder.repository.ItemReviewRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Backfill 1 lần khi khởi động: các đánh giá có sẵn TRƯỚC khi thêm kiểm duyệt
 * sẽ có Status = NULL (ddl-auto chỉ thêm cột, không set giá trị cho dòng cũ).
 * Vì hiển thị công khai chỉ tính APPROVED, ta gán dòng cũ -> APPROVED để không bị ẩn.
 * Idempotent: chỉ chạm vào dòng có Status IS NULL nên chạy nhiều lần vẫn an toàn.
 */
@Component
public class ReviewStatusBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewStatusBackfill.class);

    private final ReviewRepository reviewRepository;
    private final ItemReviewRepository itemReviewRepository;

    public ReviewStatusBackfill(ReviewRepository reviewRepository, ItemReviewRepository itemReviewRepository) {
        this.reviewRepository = reviewRepository;
        this.itemReviewRepository = itemReviewRepository;
    }

    @Override
    public void run(String... args) {
        int pitch = reviewRepository.backfillApprovedWhereStatusNull();
        int product = itemReviewRepository.backfillApprovedWhereStatusNull();
        if (pitch > 0 || product > 0) {
            log.info("Backfill kiểm duyệt: {} đánh giá sân + {} đánh giá sản phẩm -> APPROVED.", pitch, product);
        }
    }
}
