package com.example.FieldFinder.repository;


import com.example.FieldFinder.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT oi.product.productId, oi.product.name, oi.product.imageUrl, SUM(oi.quantity), SUM(oi.quantity * oi.price) " +
            "FROM OrderItem oi GROUP BY oi.product.productId, oi.product.name, oi.product.imageUrl " +
            "ORDER BY SUM(oi.quantity) DESC")
    List<Object[]> findTopSellingProductsWithRevenue(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COALESCE(SUM(oi.quantity * oi.price), 0) FROM OrderItem oi " +
            "WHERE oi.order.status IN :statuses")
    Double sumRevenueByOrderStatuses(@Param("statuses") List<com.example.FieldFinder.Enum.OrderStatus> statuses);

    @Query("SELECT CAST(oi.order.createdAt AS date), COALESCE(SUM(oi.quantity * oi.price), 0) " +
            "FROM OrderItem oi " +
            "WHERE oi.order.status IN :statuses " +
            "AND CAST(oi.order.createdAt AS date) BETWEEN :startDate AND :endDate " +
            "GROUP BY CAST(oi.order.createdAt AS date) " +
            "ORDER BY CAST(oi.order.createdAt AS date)")
    List<Object[]> findProductRevenueByDateRange(
            @Param("statuses") List<com.example.FieldFinder.Enum.OrderStatus> statuses,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);
}