package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Item_Review;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemReviewRepository extends JpaRepository<Item_Review, Long> {
    List<Item_Review> findByProduct(Product product);
    List<Item_Review> findByUser(User user);
}
