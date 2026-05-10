package com.example.FieldFinder.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MLRetrieveByImageRequest {

    @JsonProperty("image_base64")
    private String imageBase64;

    @JsonProperty("caption")
    private String caption;

    @JsonProperty("gemini_tags")
    @Builder.Default
    private List<String> geminiTags = new ArrayList<>();

    @JsonProperty("category_ids")
    @Builder.Default
    private List<Long> categoryIds = new ArrayList<>();

    @JsonProperty("top_k")
    @Builder.Default
    private int topK = 10;

    @JsonProperty("retrieve_k")
    @Builder.Default
    private int retrieveK = 30;

    @JsonProperty("item_type")
    @Builder.Default
    private String itemType = "PRODUCT";

    @JsonProperty("user_id")
    private String userId;
}