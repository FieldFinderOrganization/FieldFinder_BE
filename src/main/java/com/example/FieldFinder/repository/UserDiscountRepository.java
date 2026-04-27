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

}