package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDiscountRepository extends JpaRepository<UserDiscount, UUID> {

    boolean existsByUserAndDiscount(User user, Discount discount);

    Optional<UserDiscount> findByUserAndDiscount(User user, Discount discount);

    List<UserDiscount> findByUser(User user);

    List<UserDiscount> findByUserAndIsUsedFalse(User user);

}
