package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /** LOAD graph: category + variants (1 bag). discounts lazy + @BatchSize. */
    @EntityGraph(value = "Product.listView", type = EntityGraph.EntityGraphType.LOAD)
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(value = "Product.listView", type = EntityGraph.EntityGraphType.LOAD)
    @Query("SELECT p FROM Product p WHERE p.productId IN :ids")
    List<Product> findAllListViewByIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT p FROM Product p JOIN p.variants v GROUP BY p ORDER BY SUM(v.soldQuantity) DESC")
    List<Product> findTopSellingProducts(Pageable pageable);

    @Query("SELECT COUNT(p) FROM Product p")
    long countAllProducts();

    @Query("SELECT p.category.name, COUNT(p) FROM Product p GROUP BY p.category.name ORDER BY COUNT(p) DESC")
    List<Object[]> countByCategory();

    @Query("SELECT COALESCE(SUM(v.soldQuantity), 0) FROM ProductVariant v")
    Long sumTotalSold();

    @Query(value = "SELECT DISTINCT p.* FROM products p " +
            "WHERE LOWER(p.name) IN (:keywords) " +
            "OR LOWER(p.brand) IN (:keywords) " +
            "OR LOWER(p.tags) LIKE CONCAT('%', :firstKeyword, '%')", nativeQuery = true)
    List<Product> findByKeywords(@Param("keywords") List<String> keywords,
                                 @Param("firstKeyword") String firstKeyword);

    @Query("SELECT p.productId, p.imagePhash FROM Product p WHERE p.imagePhash IS NOT NULL")
    List<Object[]> findAllProductIdAndPhash();

    @Query("SELECT p FROM Product p WHERE p.imageUrl IS NOT NULL AND p.imagePhash IS NULL")
    List<Product> findAllNeedingPhashBackfill();

    @Query("SELECT p FROM Product p WHERE p.productId <> :excludeId AND " +
           "(p.category.categoryId = :categoryId OR p.brand = :brand OR p.sex = :sex)")
    List<Product> findSimilarProducts(@Param("excludeId") Long excludeId,
                                      @Param("categoryId") Long categoryId,
                                      @Param("brand") String brand,
                                      @Param("sex") String sex,
                                      org.springframework.data.domain.Pageable pageable);

    @Query("SELECT p FROM Product p WHERE " +
           "LOWER(p.category.name) LIKE '%football%' OR " +
           "LOWER(p.category.name) LIKE '%bóng đá%' OR " +
           "LOWER(p.category.name) LIKE '%giày%' OR " +
           "LOWER(p.category.name) LIKE '%áo%' OR " +
           "LOWER(p.name) LIKE '%bóng đá%' OR " +
           "LOWER(p.name) LIKE '%đá banh%'")
    List<Product> findFootballProducts(org.springframework.data.domain.Pageable pageable);
}