package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ItemReviewRequestDTO;
import com.example.FieldFinder.dto.req.ItemReviewUpdateRequestDTO;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;
import com.example.FieldFinder.entity.Item_Review;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ItemReviewRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.ItemReviewService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemReviewServiceImpl implements ItemReviewService {

    private final ItemReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public ItemReviewResponseDTO createReview(ItemReviewRequestDTO request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find product!"));

        Item_Review review = Item_Review.builder()
                .user(user)
                .product(product)
                .rating(request.getRating())
                .comment(request.getComment())
                .createdAt(LocalDateTime.now())
                .build();

        Item_Review saved = reviewRepository.save(review);

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public ItemReviewResponseDTO updateReview(Long reviewId, ItemReviewUpdateRequestDTO request) {
        Item_Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));

        review.setRating(request.getRating());
        review.setComment(request.getComment());

        Item_Review updated = reviewRepository.save(review);
        return mapToResponse(updated);
    }

    @Override
    public void deleteReview(Long reviewId) {
        Item_Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));
        reviewRepository.delete(review);
    }

    @Override
    public List<ItemReviewResponseDTO> getReviewsByProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm"));
        return reviewRepository.findByProduct(product).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ItemReviewResponseDTO> getReviewsByUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        return reviewRepository.findByUser(user).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ItemReviewResponseDTO> getAllItemReviews() {
        return reviewRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private ItemReviewResponseDTO mapToResponse(Item_Review review) {
        return ItemReviewResponseDTO.builder()
                .reviewId(review.getReviewId())
                .userId(review.getUser().getUserId())
                .userName(review.getUser().getName())
                .productId(review.getProduct().getProductId())
                .productName(review.getProduct().getName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
