package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDiscountRepository extends JpaRepository<UserDiscount, UUID> {

    boolean existsByUserAndDiscount(User user, Discount discount);

    Optional<UserDiscount> findByUserAndDiscount(User user, Discount discount);

    List<UserDiscount> findByUser(User user);

    @Query("SELECT ud.discount.discountId FROM UserDiscount ud WHERE ud.user.userId = :userId AND ud.isUsed = true")
    List<UUID> findUsedDiscountIdsByUserId(@Param("userId") UUID userId);

    List<UserDiscount> findByUserAndIsUsedFalse(User user);

}
