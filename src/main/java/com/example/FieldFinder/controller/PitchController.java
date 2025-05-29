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
    public PitchResponseDTO createPitch(@RequestBody PitchRequestDTO dto) {
        return pitchService.createPitch(dto);
    }

    @PutMapping("/{pitchId}")
    public PitchResponseDTO updatePitch(@PathVariable UUID pitchId, @RequestBody PitchRequestDTO dto) {
        return pitchService.updatePitch(pitchId, dto);
    }

    @GetMapping
    public ResponseEntity<List<PitchResponseDTO>> getAllPitches() {
        List<PitchResponseDTO> pitches = pitchService.getAllPitches();
        return ResponseEntity.ok(pitches);
    }

    @GetMapping("/provider/{providerAddressId}")
    public List<PitchResponseDTO> getPitchesByProviderAddressId(@PathVariable UUID providerAddressId) {
        return pitchService.getPitchesByProviderAddressId(providerAddressId);
    }
    @DeleteMapping("/{pitchId}")
    public ResponseEntity<Void> deletePitch(@PathVariable UUID pitchId) {
        pitchService.deletePitch(pitchId);
        return ResponseEntity.noContent().build(); // 204 No Content
    }
    @GetMapping("/{pitchId}")
    public ResponseEntity<PitchResponseDTO> getPitchById(@PathVariable UUID pitchId) {
        PitchResponseDTO pitch = pitchService.getPitchById(pitchId);
        return ResponseEntity.ok(pitch);
    }


}