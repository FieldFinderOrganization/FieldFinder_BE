package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchResponseDTO createPitch(PitchRequestDTO dto);
    PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO dto);
    List<PitchResponseDTO> getPitchesByProviderAddressId(UUID providerAddressId);
}