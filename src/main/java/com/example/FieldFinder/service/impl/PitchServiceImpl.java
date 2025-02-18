package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.PitchDto;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.mapper.PitchMapper;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.service.PitchService;
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
    public PitchDto createPitch(PitchDto pitchDTO) {
        Pitch pitch = PitchMapper.INSTANCE.toEntity(pitchDTO);
        return PitchMapper.INSTANCE.toDTO(pitchRepository.save(pitch));
    }

    @Override
    public PitchDto getPitchById(UUID pitchId) {
        return pitchRepository.findById(pitchId)
                .map(PitchMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<PitchDto> getAllPitches() {
        return pitchRepository.findAll()
                .stream()
                .map(PitchMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }
}
