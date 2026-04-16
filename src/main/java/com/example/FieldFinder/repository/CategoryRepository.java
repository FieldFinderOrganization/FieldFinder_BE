package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    boolean existsByName(String name);
    List<Category> findByParent_CategoryId(Long parentId);
    List<Category> findByNameContainingIgnoreCase(String keyword);
}