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
public class MLRetrieveResponse {

    @JsonProperty("query")
    private String query;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("results")
    private List<MLItemResult> results;
}