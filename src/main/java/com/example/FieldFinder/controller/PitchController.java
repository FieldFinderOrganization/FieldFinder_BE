package com.example.FieldFinder.controller;
import com.example.FieldFinder.dto.PitchDto;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.service.PaymentService;
import com.example.FieldFinder.service.PitchService;
import lombok.RequiredArgsConstructor;
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
    @GetMapping
    public ResponseEntity<List<PitchDto>> getAllPitches() {
        return ResponseEntity.ok(pitchService.getAllPitches());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PitchDto> getPitchById(@PathVariable UUID id) {
        return ResponseEntity.ok(pitchService.getPitchById(id));
    }

    @PostMapping
    public ResponseEntity<PitchDto> createPitch(@RequestBody PitchDto pitch) {
        return ResponseEntity.ok(pitchService.createPitch(pitch));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PitchDto> updatePitch(@PathVariable UUID id, @RequestBody Pitch pitch) {
        return ResponseEntity.ok(pitchService.updatePitch(id, pitch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePitch(@PathVariable UUID id) {
        pitchService.deletePitch(id);
        return ResponseEntity.noContent().build();
    }
}
