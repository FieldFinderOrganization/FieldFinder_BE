package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @Query("SELECT p FROM Product p JOIN p.variants v GROUP BY p ORDER BY SUM(v.soldQuantity) DESC")
    List<Product> findTopSellingProducts(Pageable pageable);

    @Query(value = "SELECT DISTINCT p.* FROM products p " +
            "WHERE LOWER(p.name) IN (:keywords) " +
            "OR LOWER(p.brand) IN (:keywords) " +
            "OR LOWER(p.tags) LIKE CONCAT('%', :firstKeyword, '%')",
            nativeQuery = true)
    List<Product> findByKeywords(@Param("keywords") List<String> keywords,
                                 @Param("firstKeyword") String firstKeyword);
}