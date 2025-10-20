package com.example.FieldFinder.dto.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemReviewRequestDTO {
    private UUID userId;
    private Long productId;
    private int rating;
    private String comment;
}
