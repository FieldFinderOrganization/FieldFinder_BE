package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByUserId(UUID userId);

    @Query("SELECT u.name FROM User u WHERE u.userId = :id")
    String findNameByProviderId(@Param("id") UUID providerId);

    Page<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String name, String email, Pageable pageable);

    @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
    List<Object[]> countByRole();

    @Query("SELECT u.status, COUNT(u) FROM User u GROUP BY u.status")
    List<Object[]> countByStatus();

    @Query("SELECT u FROM User u WHERE " +
            "(:search = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "AND (:status IS NULL OR u.status = :status) " +
            "AND (:role IS NULL OR u.role = :role)")
    Page<User> findWithFilters(@Param("search") String search,
                               @Param("status") User.Status status,
                               @Param("role") User.Role role,
                               Pageable pageable);

    /**
     * Job đêm: tính lại tier + total_spent12m cho TOÀN BỘ user bằng 1 câu UPDATE...JOIN
     * (set-based, không N+1). Chi tiêu = tổng đơn PAID/CONFIRMED/DELIVERED trong cửa sổ :since.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE users u " +
            "LEFT JOIN (SELECT o.user_id AS uid, COALESCE(SUM(o.total_amount), 0) AS spent " +
            "           FROM orders o " +
            "           WHERE o.status IN ('PAID','CONFIRMED','DELIVERED') " +
            "             AND COALESCE(o.payment_time, o.created_at) >= :since " +
            "           GROUP BY o.user_id) s ON s.uid = u.user_id " +
            "SET u.total_spent12m = COALESCE(s.spent, 0), " +
            "    u.tier = CASE WHEN COALESCE(s.spent, 0) >= :diamond THEN 'DIAMOND' " +
            "                  WHEN COALESCE(s.spent, 0) >= :gold THEN 'GOLD' " +
            "                  WHEN COALESCE(s.spent, 0) >= :vip THEN 'VIP' " +
            "                  ELSE 'MEMBER' END, " +
            "    u.tier_updated_at = NOW()",
            nativeQuery = true)
    int bulkRecalcTiers(@Param("since") LocalDateTime since,
                        @Param("vip") long vip,
                        @Param("gold") long gold,
                        @Param("diamond") long diamond);

    /**
     * Tìm user mà tier tính từ chi tiêu CAO HƠN tier đang lưu (cần grant voucher + email
     * trước khi bulk update). Bình thường rỗng vì hook per-order đã xử lý lên hạng.
     */
    @Query(value = "SELECT BIN_TO_UUID(u.user_id) FROM users u " +
            "LEFT JOIN (SELECT o.user_id AS uid, COALESCE(SUM(o.total_amount), 0) AS spent " +
            "           FROM orders o " +
            "           WHERE o.status IN ('PAID','CONFIRMED','DELIVERED') " +
            "             AND COALESCE(o.payment_time, o.created_at) >= :since " +
            "           GROUP BY o.user_id) s ON s.uid = u.user_id " +
            "WHERE (CASE WHEN COALESCE(s.spent, 0) >= :diamond THEN 3 " +
            "            WHEN COALESCE(s.spent, 0) >= :gold THEN 2 " +
            "            WHEN COALESCE(s.spent, 0) >= :vip THEN 1 ELSE 0 END) > " +
            "      (CASE COALESCE(u.tier, 'MEMBER') WHEN 'DIAMOND' THEN 3 " +
            "            WHEN 'GOLD' THEN 2 WHEN 'VIP' THEN 1 ELSE 0 END)",
            nativeQuery = true)
    List<String> findUserIdsNeedingTierUpgrade(@Param("since") LocalDateTime since,
                                               @Param("vip") long vip,
                                               @Param("gold") long gold,
                                               @Param("diamond") long diamond);
}