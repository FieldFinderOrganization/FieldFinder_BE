package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscountRepository extends JpaRepository<Discount, UUID> {
    boolean existsByCode(String code);

    Optional<Discount> findByCode(String discountCode);

    @Query("SELECT DISTINCT d FROM Discount d " +
            "LEFT JOIN FETCH d.applicableProducts " +
            "LEFT JOIN FETCH d.applicableCategories " +
            "WHERE d.status = 'ACTIVE' " +
            "AND CURRENT_DATE BETWEEN d.startDate AND d.endDate " +
            "AND (" +
            "   d.scope = 'GLOBAL' " +
            "   OR (d.scope = 'SPECIFIC_PRODUCT' AND EXISTS (" +
            "       SELECT 1 FROM d.applicableProducts p2 WHERE p2.productId = :productId)) " +
            "   OR (d.scope = 'CATEGORY' AND EXISTS (" +
            "       SELECT 1 FROM d.applicableCategories c2 WHERE c2.categoryId IN :categoryIds)) " +
            ")")
    List<Discount> findApplicableDiscountsForProduct(
            @Param("productId") Long productId,
            @Param("categoryIds") List<Long> categoryIds
    );

    /**
     * Batch version: load tất cả discount applicable cho list productIds + categoryIds trong 1 query.
     * Caller dùng map theo product/category để dispatch lại từng product.
     */
    @Query("SELECT DISTINCT d FROM Discount d " +
            "LEFT JOIN FETCH d.applicableProducts " +
            "LEFT JOIN FETCH d.applicableCategories " +
            "WHERE d.status = 'ACTIVE' " +
            "AND CURRENT_DATE BETWEEN d.startDate AND d.endDate " +
            "AND (" +
            "   d.scope = 'GLOBAL' " +
            "   OR (d.scope = 'SPECIFIC_PRODUCT' AND EXISTS (" +
            "       SELECT 1 FROM d.applicableProducts p2 WHERE p2.productId IN :productIds)) " +
            "   OR (d.scope = 'CATEGORY' AND EXISTS (" +
            "       SELECT 1 FROM d.applicableCategories c2 WHERE c2.categoryId IN :categoryIds)) " +
            ")")
    List<Discount> findApplicableDiscountsForProductsBatch(
            @Param("productIds") List<Long> productIds,
            @Param("categoryIds") List<Long> categoryIds
    );

    /**
     * Trừ lượt sử dụng atomic — quantity = tổng lượt DÙNG toàn hệ thống (trừ lúc checkout,
     * không trừ lúc lưu ví). Trả 0 row = mã đã hết lượt (guard race khi 2 user dùng đồng thời).
     */
    @Modifying
    @Query("UPDATE Discount d SET d.quantity = d.quantity - 1 " +
            "WHERE d.discountId = :id AND d.quantity > 0")
    int decrementQuantity(@Param("id") UUID id);

    /** Hoàn lượt sử dụng khi hủy đơn/booking đã dùng mã. */
    @Modifying
    @Query("UPDATE Discount d SET d.quantity = d.quantity + 1 WHERE d.discountId = :id")
    int incrementQuantity(@Param("id") UUID id);
}
