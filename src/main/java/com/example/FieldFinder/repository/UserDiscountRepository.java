package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDiscountRepository extends JpaRepository<UserDiscount, UUID> {

    boolean existsByUserAndDiscount(User user, Discount discount);

    Optional<UserDiscount> findByUserAndDiscount(User user, Discount discount);

    List<UserDiscount> findByUser_UserId(UUID userID);

    /**
     * Wallet query: JOIN FETCH discount + applicable collections để tránh lazy-loading
     * trả về empty set khi serialize bên ngoài session.
     * DISTINCT để tránh duplicate rows do LEFT JOIN FETCH trên ManyToMany.
     */
    @Query("SELECT DISTINCT ud FROM UserDiscount ud " +
            "JOIN FETCH ud.discount d " +
            "LEFT JOIN FETCH d.applicableProducts " +
            "LEFT JOIN FETCH d.applicableCategories " +
            "WHERE ud.user.userId = :userId")
    List<UserDiscount> findWalletByUserId(@Param("userId") UUID userId);

    @Query("SELECT ud.discount.discountId FROM UserDiscount ud WHERE ud.user.userId = :userId AND ud.isUsed = true")
    List<UUID> findUsedDiscountIdsByUserId(@Param("userId") UUID userId);

//    List<UserDiscount> findByUserAndIsUsedFalse(User user);

    List<UserDiscount> findByUser_UserIdAndIsUsedFalse(UUID userId);

    @Transactional
    @Modifying
    @Query(value = "INSERT INTO user_discounts (id, user_id, discount_id, is_used, saved_at) " +
            "SELECT UUID_TO_BIN(UUID()), u.user_id, :discountId, false, NOW() " +
            "FROM users u " +
            "WHERE NOT EXISTS (" +
            "  SELECT 1 FROM user_discounts ud " +
            "  WHERE ud.user_id = u.user_id AND ud.discount_id = :discountId" +
            ")",
            nativeQuery = true)
    void bulkAssignToAllUsers(@Param("discountId") UUID discountId);

    /**
     * Gán 1 mã cho mọi user thuộc các hạng trong :tiers (set-based, 1 câu).
     * NULL tier coi như MEMBER.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO user_discounts (id, user_id, discount_id, is_used, saved_at) " +
            "SELECT UUID_TO_BIN(UUID()), u.user_id, :discountId, false, NOW() " +
            "FROM users u " +
            "WHERE COALESCE(u.tier, 'MEMBER') IN (:tiers) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM user_discounts ud " +
            "  WHERE ud.user_id = u.user_id AND ud.discount_id = :discountId" +
            ")",
            nativeQuery = true)
    int bulkAssignToUsersByTiers(@Param("discountId") UUID discountId,
                                 @Param("tiers") List<String> tiers);

    /**
     * Gán cho 1 user mọi mã PROMOTION đang ACTIVE, còn hạn mà hạng của user đủ điều kiện
     * (min_tier NULL = public, hoặc min_tier nằm trong :tiers = các hạng <= hạng user).
     * Dùng cho cả welcome voucher khi đăng ký lẫn grant khi lên hạng — idempotent nhờ NOT EXISTS.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO user_discounts (id, user_id, discount_id, is_used, saved_at) " +
            "SELECT UUID_TO_BIN(UUID()), :userId, d.discount_id, false, NOW() " +
            "FROM discounts d " +
            "WHERE d.status = 'ACTIVE' AND d.end_date >= CURDATE() AND d.kind = 'PROMOTION' " +
            "AND (d.min_tier IS NULL OR d.min_tier IN (:tiers)) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM user_discounts ud " +
            "  WHERE ud.user_id = :userId AND ud.discount_id = d.discount_id" +
            ")",
            nativeQuery = true)
    int bulkAssignEligibleDiscountsToUser(@Param("userId") UUID userId,
                                          @Param("tiers") List<String> tiers);
}