package com.example.FieldFinder.dto;

package com.pitchbooking.application.dto;

import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewDto {
    private UUID reviewId;
    private UUID userId;
    private UUID pitchId;
    private String content;
    private Integer rating;
}
