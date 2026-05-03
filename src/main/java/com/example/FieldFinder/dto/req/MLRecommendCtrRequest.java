package com.example.FieldFinder.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MLRecommendCtrRequest {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("candidate_ids")
    private List<String> candidateIds;

    @JsonProperty("item_types")
    private List<String> itemTypes;

    @JsonProperty("context")
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();
}