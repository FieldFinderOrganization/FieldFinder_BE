package com.example.FieldFinder.service;

package com.pitchbooking.application.service;

import com.pitchbooking.application.dto.ReviewDTO;
import java.util.List;
import java.util.UUID;

public interface ReviewService {
    ReviewDTO createReview(ReviewDTO reviewDTO);
    List<ReviewDTO> getAllReviews();
}
