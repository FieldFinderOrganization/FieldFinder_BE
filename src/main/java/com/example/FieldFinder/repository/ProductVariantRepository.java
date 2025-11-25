package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant,Long> {

    Optional<ProductVariant> findByProduct_ProductIdAndSize(Long id, String size);
}
