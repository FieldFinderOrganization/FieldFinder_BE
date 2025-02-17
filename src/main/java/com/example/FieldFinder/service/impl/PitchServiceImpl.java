package com.example.FieldFinder.service.impl;

package com.pitchbooking.application.service.impl;

import com.pitchbooking.application.dto.PitchDTO;
import com.pitchbooking.application.entity.Pitch;
import com.pitchbooking.application.mapper.PitchMapper;
import com.pitchbooking.application.repository.PitchRepository;
import com.pitchbooking.application.service.PitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PitchServiceImpl implements PitchService {
    private final PitchRepository pitchRepository;

    @Override
    public PitchDTO createPitch(PitchDTO pitchDTO) {
        Pitch pitch = PitchMapper.INSTANCE.toEntity(pitchDTO);
        return PitchMapper.INSTANCE.toDTO(pitchRepository.save(pitch));
    }

    @Override
    public PitchDTO getPitchById(UUID pitchId) {
        return pitchRepository.findById(pitchId)
                .map(PitchMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<PitchDTO> getAllPitches() {
        return pitchRepository.findAll()
                .stream()
                .map(PitchMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }
}
