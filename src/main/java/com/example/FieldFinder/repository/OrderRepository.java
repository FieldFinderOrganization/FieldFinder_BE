package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUser(User user);
    List<Order> findAllByStatus(OrderStatus status);
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime threshold);

    long countByStatus(OrderStatus status);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status IN :statuses")
    Double sumTotalAmountByStatuses(@Param("statuses") List<OrderStatus> statuses);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM Order o LEFT JOIN o.user u " +
            "WHERE (:search = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
            "AND (:status IS NULL OR o.status = :status) " +
            "AND (:startDate IS NULL OR o.createdAt >= :startDate) " +
            "AND (:endDate IS NULL OR o.createdAt <= :endDate) " +
            "AND (:minAmount IS NULL OR o.totalAmount >= :minAmount) " +
            "AND (:maxAmount IS NULL OR o.totalAmount <= :maxAmount) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findWithFilters(@Param("search") String search,
                                @Param("status") OrderStatus status,
                                @Param("startDate") LocalDateTime startDate,
                                @Param("endDate") LocalDateTime endDate,
                                @Param("minAmount") Double minAmount,
                                @Param("maxAmount") Double maxAmount,
                                Pageable pageable);

    @Query("SELECT o FROM Order o LEFT JOIN o.user u " +
            "WHERE (:search = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
            "AND (:status IS NULL OR o.status = :status) " +
            "AND (:startDate IS NULL OR o.createdAt >= :startDate) " +
            "AND (:endDate IS NULL OR o.createdAt <= :endDate) " +
            "AND (:minAmount IS NULL OR o.totalAmount >= :minAmount) " +
            "AND (:maxAmount IS NULL OR o.totalAmount <= :maxAmount) " +
            "ORDER BY (SELECT COUNT(o2) FROM Order o2 WHERE o2.user = o.user) DESC, o.createdAt DESC")
    Page<Order> findWithFiltersSortByCustomerCount(@Param("search") String search,
                                                   @Param("status") OrderStatus status,
                                                   @Param("startDate") LocalDateTime startDate,
                                                   @Param("endDate") LocalDateTime endDate,
                                                   @Param("minAmount") Double minAmount,
                                                   @Param("maxAmount") Double maxAmount,
                                                   Pageable pageable);
}