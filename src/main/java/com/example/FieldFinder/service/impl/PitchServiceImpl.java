package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.service.PitchService;
import jakarta.transaction.Transactional;
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

    @Override
    @Transactional
    public PitchResponseDTO createPitch(PitchRequestDTO request) {
        Pitch pitch = Pitch.builder()
                .owner(null) // Assign owner based on user service
                .name(request.getName())
                .address(request.getAddress())
                .type(request.getType())
                .price(request.getPrice())
                .imageUrl(request.getImageUrl())
                .description(request.getDescription())
                .build();

        pitch = pitchRepository.save(pitch);
        return mapToDTO(pitch);
    }

    @Override
    @Transactional
    public PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO request) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found"));

        pitch.setName(request.getName());
        pitch.setAddress(request.getAddress());
        pitch.setType(request.getType());
        pitch.setPrice(request.getPrice());
        pitch.setImageUrl(request.getImageUrl());
        pitch.setDescription(request.getDescription());

        pitch = pitchRepository.save(pitch);
        return mapToDTO(pitch);
    }

    @Override
    @Transactional
    public void deletePitch(UUID pitchId) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found"));
        // Add check for bookings before deleting
        pitchRepository.delete(pitch);
    }

    @Override
    public List<PitchResponseDTO> getPitchesByOwner(UUID userId) {
        return pitchRepository.findByOwner_UserId(userId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    public List<PitchResponseDTO> searchPitches(PitchRequestDTO criteria) {
        return pitchRepository.findByTypeAndPriceLessThanEqualAndAddressContainingIgnoreCase(
                        criteria.getType(), criteria.getPrice(), criteria.getAddress())
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    private PitchResponseDTO mapToDTO(Pitch pitch) {
        PitchResponseDTO dto = new PitchResponseDTO();
        dto.setPitchId(pitch.getPitchId());
        dto.setOwnerId(pitch.getOwner().getUserId());
        dto.setName(pitch.getName());
        dto.setAddress(pitch.getAddress());
        dto.setType(pitch.getType());
        dto.setPrice(pitch.getPrice());
        dto.setImageUrl(pitch.getImageUrl());
        dto.setDescription(pitch.getDescription());
        return dto;
    }
}
