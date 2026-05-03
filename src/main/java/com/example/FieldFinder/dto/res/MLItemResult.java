package com.example.FieldFinder.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MLItemResult {

    @JsonProperty("item_id")
    private String itemId;

    @JsonProperty("item_type")
    private String itemType;

    @JsonProperty("item_key")
    private String itemKey;

    @JsonProperty("name")
    private String name;

    @JsonProperty("category")
    private String category;

    @JsonProperty("env")
    private String environment;

    @JsonProperty("brand")
    private String brand;

    @JsonProperty("price")
    private Double price;

    @JsonProperty("score")
    private Double score;

    @JsonProperty("sasrec_score")
    private Double sasrecScore;

    @JsonProperty("final_score")
    private Double finalScore;
}