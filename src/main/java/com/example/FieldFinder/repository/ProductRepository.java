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

    /**
     * Paginated list query — fetch ONLY the single-valued category, NOT the variants collection.
     * Fetching a collection here forces Hibernate to page in memory (HHH90003004): it loads every
     * matching row, dedups, and windows in-memory → slow + too few distinct rows per page. With
     * category-only the DB applies real OFFSET/LIMIT. Variants are hydrated separately by the caller
     * via {@link #findAllListViewByIds}.
     */
    @EntityGraph(attributePaths = {"category"})
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

    @Query("SELECT p FROM Product p WHERE " +
           "LOWER(p.category.name) LIKE '%football%' OR " +
           "LOWER(p.category.name) LIKE '%bóng đá%' OR " +
           "LOWER(p.category.name) LIKE '%giày%' OR " +
           "LOWER(p.category.name) LIKE '%áo%' OR " +
           "LOWER(p.name) LIKE '%bóng đá%' OR " +
           "LOWER(p.name) LIKE '%đá banh%'")
    List<Product> findFootballProducts(org.springframework.data.domain.Pageable pageable);
}