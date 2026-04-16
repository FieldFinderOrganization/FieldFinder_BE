package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findByProduct_ProductIdAndSize(Long id, String size);

    List<ProductVariant> findAllByProduct_ProductId(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.product.productId = :productId AND v.size = :size")
    Optional<ProductVariant> findByProductIdAndSizeForUpdate(
            @Param("productId") Long productId,
            @Param("size") String size);
}