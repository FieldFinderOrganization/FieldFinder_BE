package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;

import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchResponseDTO createPitch(PitchRequestDTO request);
    PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO request);
    void deletePitch(UUID pitchId);
    List<PitchResponseDTO> getPitchesByOwner(UUID ownerId);
    List<PitchResponseDTO> searchPitches(PitchRequestDTO criteria);
}
