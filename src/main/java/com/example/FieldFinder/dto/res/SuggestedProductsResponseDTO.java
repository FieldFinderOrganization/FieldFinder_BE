package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class SuggestedProductsResponseDTO {
    private List<ProductResponseDTO> similar;
    private List<ProductResponseDTO> topSelling;
    private List<ProductResponseDTO> historyBased;
}
