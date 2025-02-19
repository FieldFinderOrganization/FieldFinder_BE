package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.PitchDto;
import com.example.FieldFinder.entity.Pitch;

import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchDto createPitch(PitchDto pitchDTO);
    PitchDto getPitchById(UUID pitchId);
    List<PitchDto> getAllPitches();

    PitchDto updatePitch(UUID id, Pitch pitch);

    void deletePitch(UUID id);
}
