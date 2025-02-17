package com.example.FieldFinder.controller;

package com.footballbooking.application.controller;

import com.footballbooking.application.entity.Pitch;
import com.footballbooking.application.service.PitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pitches")
@RequiredArgsConstructor
public class PitchController {
    private final PitchService pitchService;

    @GetMapping
    public ResponseEntity<List<Pitch>> getAllPitches() {
        return ResponseEntity.ok(pitchService.getAllPitches());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Pitch> getPitchById(@PathVariable UUID id) {
        return ResponseEntity.ok(pitchService.getPitchById(id));
    }

    @PostMapping
    public ResponseEntity<Pitch> createPitch(@RequestBody Pitch pitch) {
        return ResponseEntity.ok(pitchService.createPitch(pitch));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Pitch> updatePitch(@PathVariable UUID id, @RequestBody Pitch pitch) {
        return ResponseEntity.ok(pitchService.updatePitch(id, pitch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePitch(@PathVariable UUID id) {
        pitchService.deletePitch(id);
        return ResponseEntity.noContent().build();
    }
}
