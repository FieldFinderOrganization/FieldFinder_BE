package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
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
    private final ProviderAddressRepository providerAddressRepository;

    @Override
    public PitchResponseDTO createPitch(PitchRequestDTO dto) {
        // Tìm providerAddress bên trong method
        ProviderAddress providerAddress = providerAddressRepository.findById(dto.getProviderAddressId())
                .orElseThrow(() -> new RuntimeException("ProviderAddress not found!"));

        Pitch pitch = Pitch.builder()
                .providerAddress(providerAddress)
                .name(dto.getName())
                .type(dto.getType())
                .price(dto.getPrice())
                .description(dto.getDescription())
                .build();

        pitch = pitchRepository.save(pitch);
        return mapToDto(pitch);
    }

    @Override
    public PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO dto) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));
        pitch.setName(dto.getName());
        pitch.setType(dto.getType());
        pitch.setPrice(dto.getPrice());
        pitch.setDescription(dto.getDescription());
        pitch = pitchRepository.save(pitch);
        return mapToDto(pitch);
    }

    @Override
    public List<PitchResponseDTO> getPitchesByProviderAddressId(UUID providerAddressId) {
        return pitchRepository.findByProviderAddressProviderAddressId(providerAddressId)
                .stream().map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private PitchResponseDTO mapToDto(Pitch pitch) {
        PitchResponseDTO dto = new PitchResponseDTO();
        dto.setPitchId(pitch.getPitchId());
        dto.setProviderAddressId(pitch.getProviderAddress().getProviderAddressId());
        dto.setName(pitch.getName());
        dto.setType(pitch.getType());
        dto.setPrice(pitch.getPrice());
        dto.setDescription(pitch.getDescription());
        return dto;
    }
    @Override
    public void deletePitch(UUID pitchId) {
        if (!pitchRepository.existsById(pitchId)) {
            throw new RuntimeException("Pitch not found!");
        }
        pitchRepository.deleteById(pitchId);
    }
    @Override
    public List<PitchResponseDTO> getAllPitches() {
        return pitchRepository.findAll().stream()
                .map(PitchResponseDTO::fromEntity)
                .toList();
    }

    @Override
    public PitchResponseDTO getPitchById(UUID pitchId) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Cannot find pitch with id: " + pitchId));

        return PitchResponseDTO.fromEntity(pitch);
    }
}
