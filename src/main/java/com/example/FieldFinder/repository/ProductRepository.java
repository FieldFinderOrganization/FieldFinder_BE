package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT p FROM Product p JOIN p.variants v GROUP BY p ORDER BY SUM(v.soldQuantity) DESC")
    List<Product> findTopSellingProducts(Pageable pageable);
}