package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.service.PitchService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PitchServiceImpl implements PitchService {
    private final PitchRepository pitchRepository;
    public PitchServiceImpl(PitchRepository pitchRepository) {
        this.pitchRepository = pitchRepository;
    }
}
