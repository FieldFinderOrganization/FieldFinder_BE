package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiscountRepository extends JpaRepository<Discount, UUID> {
}
