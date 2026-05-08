package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findByProduct_ProductIdAndSize(Long id, String size);

    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product WHERE v.product.productId = :productId AND v.size = :size")
    Optional<ProductVariant> findWithProductByProductIdAndSize(
            @Param("productId") Long productId,
            @Param("size") String size);

    List<ProductVariant> findAllByProduct_ProductId(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.product.productId = :productId AND v.size = :size")
    Optional<ProductVariant> findByProductIdAndSizeForUpdate(
            @Param("productId") Long productId,
            @Param("size") String size);

    @Modifying
    @Query("UPDATE ProductVariant v "
            + "SET v.stockQuantity = v.stockQuantity - :qty, "
            + "    v.lockedQuantity = v.lockedQuantity - :qty, "
            + "    v.soldQuantity = v.soldQuantity + :qty "
            + "WHERE v.product.productId = :productId AND v.size = :size")
    int commitStockAtomic(
            @Param("productId") Long productId,
            @Param("size") String size,
            @Param("qty") int qty);

    @Modifying
    @Query("UPDATE ProductVariant v "
            + "SET v.stockQuantity = v.stockQuantity + :qty, "
            + "    v.soldQuantity = CASE WHEN v.soldQuantity - :qty < 0 THEN 0 ELSE v.soldQuantity - :qty END "
            + "WHERE v.product.productId = :productId AND v.size = :size")
    int restoreStockAtomic(
            @Param("productId") Long productId,
            @Param("size") String size,
            @Param("qty") int qty);
}