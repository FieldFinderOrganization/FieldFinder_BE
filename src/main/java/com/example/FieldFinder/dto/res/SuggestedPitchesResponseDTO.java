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
public class SuggestedPitchesResponseDTO {
    private List<PitchResponseDTO> nearby;
    private List<PitchResponseDTO> topRated;
    private List<PitchResponseDTO> visited;
}
