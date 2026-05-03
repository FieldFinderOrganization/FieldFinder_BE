package com.example.FieldFinder.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MLRecommendCtrResponse {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("scores")
    private List<CtrScore> scores;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CtrScore {
        @JsonProperty("item_id")
        private String itemId;

        @JsonProperty("item_type")
        private String itemType;

        @JsonProperty("ctr_score")
        private Double ctrScore;
    }
}