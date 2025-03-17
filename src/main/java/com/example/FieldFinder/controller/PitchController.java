package com.example.FieldFinder.controller;
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
}
