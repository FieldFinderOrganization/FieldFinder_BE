package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.FavoritePitch;
import com.example.FieldFinder.repository.FavoritePitchRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.service.FavoritePitchService;
import com.example.FieldFinder.service.PitchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FavoritePitchServiceImpl implements FavoritePitchService {

    private final FavoritePitchRepository favoritePitchRepository;
    private final PitchRepository pitchRepository;
    private final PitchService pitchService;

    public FavoritePitchServiceImpl(FavoritePitchRepository favoritePitchRepository,
                                    PitchRepository pitchRepository,
                                    PitchService pitchService) {
        this.favoritePitchRepository = favoritePitchRepository;
        this.pitchRepository = pitchRepository;
        this.pitchService = pitchService;
    }

    @Override
    @Transactional
    public void add(UUID userId, UUID pitchId) {
        // Bỏ qua nếu sân không tồn tại hoặc đã favorite (idempotent).
        if (!pitchRepository.existsById(pitchId)) return;
        if (favoritePitchRepository.existsByUserIdAndPitchId(userId, pitchId)) return;
        favoritePitchRepository.save(
                FavoritePitch.builder()
                        .userId(userId)
                        .pitchId(pitchId)
                        .build()
        );
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID pitchId) {
        favoritePitchRepository.deleteByUserIdAndPitchId(userId, pitchId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listIds(UUID userId) {
        return favoritePitchRepository.findPitchIdsByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PitchResponseDTO> listPitches(UUID userId) {
        List<UUID> ids = favoritePitchRepository.findPitchIdsByUserId(userId);
        List<PitchResponseDTO> result = new ArrayList<>(ids.size());
        for (UUID pitchId : ids) {
            try {
                result.add(pitchService.getPitchById(pitchId));
            } catch (RuntimeException ignored) {
                // Sân đã bị xoá — bỏ qua, không để hỏng cả danh sách.
            }
        }
        return result;
    }
}
