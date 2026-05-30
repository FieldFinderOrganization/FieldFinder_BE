package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Một query — dùng walk ancestor trong memory, không lazy-load parent chain. */
    @Query("SELECT c.categoryId, p.categoryId FROM Category c LEFT JOIN c.parent p")
    List<Object[]> findAllCategoryIdAndParentId();
    boolean existsByName(String name);
    List<Category> findByParent_CategoryId(Long parentId);
    List<Category> findByNameContainingIgnoreCase(String keyword);

    List<Category> findByParent_CategoryIdIn(java.util.Collection<Long> parentIds);
}