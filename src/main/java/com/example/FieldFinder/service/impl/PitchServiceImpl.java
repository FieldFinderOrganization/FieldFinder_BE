package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.specification.PitchSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PitchServiceImpl implements PitchService {

    private final PitchRepository pitchRepository;
    private final ProviderAddressRepository providerAddressRepository;
    private final BookingDetailRepository bookingDetailRepository;

    @Override
    public PitchResponseDTO createPitch(PitchRequestDTO dto) {
        ProviderAddress providerAddress = providerAddressRepository.findById(dto.getProviderAddressId())
                .orElseThrow(() -> new RuntimeException("ProviderAddress not found!"));

        Pitch pitch = Pitch.builder()
                .providerAddress(providerAddress)
                .name(dto.getName())
                .type(dto.getType())
                .price(dto.getPrice())
                .environment(dto.getEnvironment())
                .description(dto.getDescription())
                .imageUrls(dto.getImageUrls() != null ? dto.getImageUrls() : new ArrayList<>())
                .build();

        pitch = pitchRepository.save(pitch);
        return PitchResponseDTO.fromEntity(pitch);
    }

    @Override
    public PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO dto) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));
        pitch.setName(dto.getName());
        pitch.setType(dto.getType());
        pitch.setEnvironment(dto.getEnvironment());
        pitch.setPrice(dto.getPrice());
        pitch.setDescription(dto.getDescription());
        pitch.setImageUrls(dto.getImageUrls() != null ? dto.getImageUrls() : new ArrayList<>());
        pitch = pitchRepository.save(pitch);
        return PitchResponseDTO.fromEntity(pitch);
    }

    @Override
    public List<PitchResponseDTO> getPitchesByProviderAddressId(UUID providerAddressId) {
        return pitchRepository.findByProviderAddressProviderAddressId(providerAddressId)
                .stream().map(PitchResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void deletePitch(UUID pitchId) {
        if (!pitchRepository.existsById(pitchId)) {
            throw new RuntimeException("Pitch not found!");
        }
        if (bookingDetailRepository.existsByPitch_PitchId(pitchId)) {
            throw new RuntimeException("Không thể xóa sân vì đã có đơn đặt sân liên quan!");
        }
        pitchRepository.deleteById(pitchId);
    }
    @Override
    public Page<PitchResponseDTO> getAllPitches(Pageable pageable, String district, String type, String name) {
        Specification<Pitch> spec = Specification.where(PitchSpecification.hasDistrict(district))
                .and(PitchSpecification.hasType(type))
                .and(PitchSpecification.hasName(name));

        Page<Pitch> pitches = pitchRepository.findAll(spec, pageable);

        List<PitchResponseDTO> pitchResponseDTOS = pitches.getContent()
                .stream()
                .map(PitchResponseDTO::fromEntity)
                .toList();

        return new PageImpl<>(pitchResponseDTOS, pageable, pitches.getTotalElements());
    }

    @Override
    public PitchResponseDTO getPitchById(UUID pitchId) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Cannot find pitch with id: " + pitchId));

        return PitchResponseDTO.fromEntity(pitch);
    }
}