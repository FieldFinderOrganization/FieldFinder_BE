package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ReviewRequestDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    @Mock ReviewRepository reviewRepository;
    @Mock PitchRepository pitchRepository;
    @Mock UserRepository userRepository;

    @InjectMocks ReviewServiceImpl service;

    private UUID pitchId;
    private UUID userId;
    private UUID reviewId;
    private Pitch pitch;
    private User user;
    private Review review;

    @BeforeEach
    void setUp() {
        pitchId = UUID.randomUUID();
        userId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        pitch = new Pitch();
        pitch.setPitchId(pitchId);
        pitch.setName("Sân A");

        user = new User();
        user.setUserId(userId);
        user.setName("Triet");

        review = Review.builder()
                .reviewId(reviewId)
                .pitch(pitch)
                .user(user)
                .rating(5)
                .comment("Tốt lắm")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ReviewRequestDTO buildRequest() {
        ReviewRequestDTO dto = new ReviewRequestDTO();
        dto.setPitchId(pitchId);
        dto.setUserId(userId);
        dto.setRating(5);
        dto.setComment("Tốt lắm");
        return dto;
    }

    @Nested
    class addReview {
        @Test
        void valid_savesAndReturnsDTO() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(reviewRepository.findByPitch_PitchIdAndUser_UserId(pitchId, userId))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class))).thenReturn(review);

            ReviewResponseDTO result = service.addReview(buildRequest());

            assertNotNull(result);
            assertEquals(5, result.getRating());
            assertEquals("Tốt lắm", result.getComment());
            verify(reviewRepository).save(any(Review.class));
        }

        @Test
        void pitchNotFound_ThrowsException() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.addReview(buildRequest()));
            assertTrue(ex.getMessage().contains("Pitch not found"));
        }

        @Test
        void userNotFound_ThrowsException() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.addReview(buildRequest()));
            assertTrue(ex.getMessage().contains("User not found"));
        }

        @Test
        void alreadyReviewed_ThrowsException() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(reviewRepository.findByPitch_PitchIdAndUser_UserId(pitchId, userId))
                    .thenReturn(Optional.of(review));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.addReview(buildRequest()));
            assertTrue(ex.getMessage().contains("already reviewed"));
            verify(reviewRepository, never()).save(any());
        }
    }

    @Nested
    class updateReview {
        @Test
        void existing_updatesAndReturnsDTO() {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
            when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

            ReviewRequestDTO req = buildRequest();
            req.setRating(3);
            req.setComment("Bình thường");

            ReviewResponseDTO result = service.updateReview(reviewId, req);

            assertNotNull(result);
            assertEquals(3, review.getRating());
            assertEquals("Bình thường", review.getComment());
        }

        @Test
        void notFound_ThrowsException() {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateReview(reviewId, buildRequest()));
            assertTrue(ex.getMessage().contains("Review not found"));
        }
    }

    @Nested
    class deleteReview {
        @Test
        void existing_deletes() {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

            service.deleteReview(reviewId);

            verify(reviewRepository).delete(review);
        }

        @Test
        void notFound_ThrowsException() {
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.deleteReview(reviewId));
            assertTrue(ex.getMessage().contains("Review not found"));
        }
    }

    @Nested
    class getReviewsByPitch {
        @Test
        void hasData_ReturnsList() {
            when(reviewRepository.findByPitch_PitchId(pitchId)).thenReturn(List.of(review));

            List<ReviewResponseDTO> result = service.getReviewsByPitch(pitchId);

            assertEquals(1, result.size());
            assertEquals(pitchId, result.getFirst().getPitchId());
        }

        @Test
        void empty_ReturnsEmpty() {
            when(reviewRepository.findByPitch_PitchId(pitchId)).thenReturn(List.of());

            assertTrue(service.getReviewsByPitch(pitchId).isEmpty());
        }
    }

    @Nested
    class getReviewsByUser {
        @Test
        void hasData_ReturnsList() {
            when(reviewRepository.findByUser_UserId(userId)).thenReturn(List.of(review));

            List<ReviewResponseDTO> result = service.getReviewsByUser(userId);

            assertEquals(1, result.size());
            assertEquals(userId, result.getFirst().getUserId());
        }
    }

    @Nested
    class getAverageRating {
        @Test
        void returnsValue() {
            when(reviewRepository.findAverageRatingByPitchId(pitchId)).thenReturn(4.5);

            Double avg = service.getAverageRating(pitchId);

            assertEquals(4.5, avg);
        }

        @Test
        void noReviews_ReturnsNull() {
            when(reviewRepository.findAverageRatingByPitchId(pitchId)).thenReturn(null);

            assertNull(service.getAverageRating(pitchId));
        }
    }
}