package com.example.FieldFinder.dto.res;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
public class ReviewResponseDTO {
    private UUID reviewId;
    private UUID pitchId;
    private UUID userId;
    private int rating;
    private String comment;
    private String createdAt;
}
