package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.PitchDto;
import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchDto createPitch(PitchDto pitchDTO);
    PitchDto getPitchById(UUID pitchId);
    List<PitchDto> getAllPitches();
}
