package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscountRepository extends JpaRepository<Discount, UUID> {
    boolean existsByCode(String code);

    Optional<Discount> findByCode(String discountCode);

    @Query("SELECT DISTINCT d FROM Discount d " +
            "LEFT JOIN d.applicableProducts p " +
            "LEFT JOIN d.applicableCategories c " +
            "WHERE d.status = 'ACTIVE' " +
            "AND CURRENT_DATE BETWEEN d.startDate AND d.endDate " +
            "AND (" +
            "   d.scope = 'GLOBAL' " +
            "   OR (d.scope = 'SPECIFIC_PRODUCT' AND p.productId = :productId) " +
            "   OR (d.scope = 'CATEGORY' AND c.categoryId IN :categoryIds) " +
            ")")
    List<Discount> findApplicableDiscountsForProduct(
            @Param("productId") Long productId,
            @Param("categoryIds") List<Long> categoryIds
    );
}
