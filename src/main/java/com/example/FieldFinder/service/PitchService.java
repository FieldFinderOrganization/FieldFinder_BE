package com.example.FieldFinder.service;

package com.pitchbooking.application.service;

import com.pitchbooking.application.dto.PitchDTO;
import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchDTO createPitch(PitchDTO pitchDTO);
    PitchDTO getPitchById(UUID pitchId);
    List<PitchDTO> getAllPitches();
}
