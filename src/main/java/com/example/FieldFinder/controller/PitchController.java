package com.example.FieldFinder.controller;
import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.service.PitchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pitches")
public class PitchController {
    private final PitchService pitchService;

    public PitchController(PitchService pitchService) {
        this.pitchService = pitchService;
    }

    @PostMapping
    public ResponseEntity<PitchResponseDTO> createPitch(@RequestBody PitchRequestDTO request) {
        return ResponseEntity.ok(pitchService.createPitch(request));
    }

    @PutMapping("/{pitchId}")
    public ResponseEntity<PitchResponseDTO> updatePitch(@PathVariable UUID pitchId, @RequestBody PitchRequestDTO request) {
        return ResponseEntity.ok(pitchService.updatePitch(pitchId, request));
    }

    @DeleteMapping("/{pitchId}")
    public ResponseEntity<Void> deletePitch(@PathVariable UUID pitchId) {
        pitchService.deletePitch(pitchId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/owner/{ownerId}")
    public ResponseEntity<List<PitchResponseDTO>> getPitchesByOwner(@PathVariable UUID ownerId) {
        return ResponseEntity.ok(pitchService.getPitchesByOwner(ownerId));
    }

    @PostMapping("/search")
    public ResponseEntity<List<PitchResponseDTO>> searchPitches(@RequestBody PitchRequestDTO criteria) {
        return ResponseEntity.ok(pitchService.searchPitches(criteria));
    }
}