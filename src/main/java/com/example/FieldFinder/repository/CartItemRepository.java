package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.Cart_item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<Cart_item, Long> {
    List<Cart_item> findByCart(Cart cart);
}
