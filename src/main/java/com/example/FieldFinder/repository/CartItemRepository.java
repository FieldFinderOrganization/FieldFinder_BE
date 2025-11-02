package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.Cart_item;
import com.example.FieldFinder.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<Cart_item, Long> {
    List<Cart_item> findByCart(Cart cart);

    Optional<Cart_item> findByCartAndProductAndSize(Cart cart, Product product, String size);
}
