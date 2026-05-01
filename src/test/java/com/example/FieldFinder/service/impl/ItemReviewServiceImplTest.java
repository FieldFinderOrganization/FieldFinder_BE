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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItemReviewServiceImplTest {

    @Mock ItemReviewRepository reviewRepository;
    @Mock UserRepository userRepository;
    @Mock ProductRepository productRepository;

    @InjectMocks ItemReviewServiceImpl service;

    private UUID userId;
    private User user;
    private Product product;
    private Item_Review review;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        user = new User();
        user.setUserId(userId);
        user.setName("Triet");

        product = Product.builder()
                .productId(1L)
                .name("Áo đấu")
                .build();

        review = Item_Review.builder()
                .reviewId(10L)
                .user(user)
                .product(product)
                .rating(4)
                .comment("Đẹp lắm")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    class createReview {
        @Test
        void valid_savesAndReturnsDTO() {
            ItemReviewRequestDTO req = new ItemReviewRequestDTO();
            req.setUserId(userId);
            req.setProductId(1L);
            req.setRating(4);
            req.setComment("Đẹp lắm");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(reviewRepository.save(any(Item_Review.class))).thenReturn(review);

            ItemReviewResponseDTO result = service.createReview(req);

            assertNotNull(result);
            assertEquals(4, result.getRating());
            assertEquals("Đẹp lắm", result.getComment());
            verify(reviewRepository).save(any(Item_Review.class));
        }

        @Test
        void userNotFound_ThrowsException() {
            ItemReviewRequestDTO req = new ItemReviewRequestDTO();
            req.setUserId(userId);
            req.setProductId(1L);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.createReview(req));
        }

        @Test
        void productNotFound_ThrowsException() {
            ItemReviewRequestDTO req = new ItemReviewRequestDTO();
            req.setUserId(userId);
            req.setProductId(99L);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.createReview(req));
        }
    }

    @Nested
    class updateReview {
        @Test
        void existing_updatesAndReturnsDTO() {
            when(reviewRepository.findById(10L)).thenReturn(Optional.of(review));
            when(reviewRepository.save(any(Item_Review.class))).thenAnswer(inv -> inv.getArgument(0));

            ItemReviewUpdateRequestDTO req = new ItemReviewUpdateRequestDTO();
            req.setRating(2);
            req.setComment("Hơi bình thường");

            ItemReviewResponseDTO result = service.updateReview(10L, req);

            assertNotNull(result);
            assertEquals(2, review.getRating());
            assertEquals("Hơi bình thường", review.getComment());
        }

        @Test
        void notFound_ThrowsException() {
            when(reviewRepository.findById(99L)).thenReturn(Optional.empty());

            ItemReviewUpdateRequestDTO req = new ItemReviewUpdateRequestDTO();
            assertThrows(ResponseStatusException.class, () -> service.updateReview(99L, req));
        }
    }

    @Nested
    class deleteReview {
        @Test
        void existing_deletes() {
            when(reviewRepository.findById(10L)).thenReturn(Optional.of(review));

            service.deleteReview(10L);

            verify(reviewRepository).delete(review);
        }

        @Test
        void notFound_ThrowsException() {
            when(reviewRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.deleteReview(99L));
        }
    }

    @Nested
    class getReviewsByProduct {
        @Test
        void hasData_ReturnsList() {
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(reviewRepository.findByProduct(product)).thenReturn(List.of(review));

            List<ItemReviewResponseDTO> result = service.getReviewsByProduct(1L);

            assertEquals(1, result.size());
            assertEquals(1L, result.getFirst().getProductId());
        }

        @Test
        void productNotFound_ThrowsException() {
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.getReviewsByProduct(99L));
        }
    }

    @Nested
    class getReviewsByUser {
        @Test
        void hasData_ReturnsList() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(reviewRepository.findByUser(user)).thenReturn(List.of(review));

            List<ItemReviewResponseDTO> result = service.getReviewsByUser(userId);

            assertEquals(1, result.size());
            assertEquals(userId, result.getFirst().getUserId());
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> service.getReviewsByUser(userId));
        }
    }

    @Nested
    class getAllItemReviews {
        @Test
        void hasData_ReturnsList() {
            when(reviewRepository.findAll()).thenReturn(List.of(review));

            List<ItemReviewResponseDTO> result = service.getAllItemReviews();

            assertEquals(1, result.size());
        }

        @Test
        void empty_ReturnsEmpty() {
            when(reviewRepository.findAll()).thenReturn(List.of());

            assertTrue(service.getAllItemReviews().isEmpty());
        }
    }
}