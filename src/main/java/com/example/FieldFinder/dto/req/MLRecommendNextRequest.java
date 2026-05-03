package com.example.FieldFinder.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MLRecommendNextRequest {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("top_k")
    private int topK;

    @JsonProperty("item_type")
    private String itemType;
}