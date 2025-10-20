package com.example.FieldFinder.dto.req;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemReviewUpdateRequestDTO {
    private int rating;
    private String comment;
}
