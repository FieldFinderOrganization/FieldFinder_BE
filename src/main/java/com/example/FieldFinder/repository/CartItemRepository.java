package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.Cart_item;
import com.example.FieldFinder.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<Cart_item, Long> {
    List<Cart_item> findByCart(Cart cart);

    Optional<Cart_item> findByCartAndProductAndSize(Cart cart, Product product, String size);

    @Query("SELECT ci from Cart_item ci " +
            "JOIN FETCH ci.product p " +
            "LEFT JOIN FETCH p.category c " +
            "WHERE ci.cart.cartId = :cartId")
    List<Cart_item> findByCartIdWithDetails(@Param("cartId") Long cartId);
}
