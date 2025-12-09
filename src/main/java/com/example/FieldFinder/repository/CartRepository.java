package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.CartStatus;
import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
public interface CartRepository extends JpaRepository<Cart, Long>{
    List<Cart> findByUser(User user);

    Optional<Cart> findById(Long cartId);

    List<Cart> findByUserAndStatusOrderByCreatedAtDesc(User user, CartStatus status);
}
