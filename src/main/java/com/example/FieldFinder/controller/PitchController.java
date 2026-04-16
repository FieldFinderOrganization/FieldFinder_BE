package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.service.PitchService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/pitches")
public class PitchController {
    private final PitchService pitchService;

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
    public ResponseEntity<Page<PitchResponseDTO>> getAllPitches(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PitchResponseDTO> pitches = pitchService.getAllPitches(pageable);
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

}