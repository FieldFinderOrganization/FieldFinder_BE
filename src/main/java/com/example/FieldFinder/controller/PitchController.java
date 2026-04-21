package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.service.PitchService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pitches")
public class PitchController {
    private final PitchService pitchService;

    private static final Set<String> VALID_SORT_FIELDS = Set.of(
            "pitchId", "name", "price", "type", "environment", "description"
    );

    public PitchController(PitchService pitchService) {
        this.pitchService = pitchService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public PitchResponseDTO createPitch(@RequestBody PitchRequestDTO dto) {
        return pitchService.createPitch(dto);
    }

    @PutMapping("/{pitchId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public PitchResponseDTO updatePitch(@PathVariable UUID pitchId, @RequestBody PitchRequestDTO dto) {
        return pitchService.updatePitch(pitchId, dto);
    }

    @GetMapping
    public ResponseEntity<Page<PitchResponseDTO>> getAllPitches(Pageable pageable,
                                                                @RequestParam(required = false) String district,
                                                                @RequestParam(required = false) String type,
                                                                @RequestParam(required = false) String name) {
        Pageable safePageable = sanitizePageable(pageable);
        Page<PitchResponseDTO> pitches = pitchService.getAllPitches(safePageable, district, type, name);
        return ResponseEntity.ok(pitches);
    }

    @GetMapping("/provider/{providerAddressId}")
    @PreAuthorize("isAuthenticated()")
    public List<PitchResponseDTO> getPitchesByProviderAddressId(@PathVariable UUID providerAddressId) {
        return pitchService.getPitchesByProviderAddressId(providerAddressId);
    }

    @DeleteMapping("/{pitchId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<Void> deletePitch(@PathVariable UUID pitchId) {
        pitchService.deletePitch(pitchId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{pitchId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PitchResponseDTO> getPitchById(@PathVariable UUID pitchId) {
        PitchResponseDTO pitch = pitchService.getPitchById(pitchId);
        return ResponseEntity.ok(pitch);
    }

    private Pageable sanitizePageable(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> validOrders = pageable.getSort().stream()
                .filter(order -> VALID_SORT_FIELDS.contains(order.getProperty()))
                .collect(Collectors.toList());

        Sort safeSort = validOrders.isEmpty() ? Sort.unsorted() : Sort.by(validOrders);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), safeSort);
    }
}