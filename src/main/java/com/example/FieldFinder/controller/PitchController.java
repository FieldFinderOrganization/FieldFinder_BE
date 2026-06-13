package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.req.PitchStatusRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.exception.PitchDeactivateBlockedException;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.RoutingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pitches")
public class PitchController {
    private final PitchService pitchService;
    private final RoutingService routingService;
    private final com.example.FieldFinder.repository.UserRepository userRepository;

    private static final Set<String> VALID_SORT_FIELDS = Set.of(
            "pitchId", "name", "price", "type", "environment", "description"
    );

    public PitchController(PitchService pitchService, RoutingService routingService,
                           com.example.FieldFinder.repository.UserRepository userRepository) {
        this.pitchService = pitchService;
        this.routingService = routingService;
        this.userRepository = userRepository;
    }

    /** Trích userId từ JWT token (giống pattern ở BookingController). */
    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String s) {
                email = s;
            }
            if (email != null) {
                return userRepository.findByEmail(email)
                        .map(u -> u.getUserId())
                        .orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
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

    /**
     * PATCH /api/pitches/{pitchId}/status
     * Body: { "targetDate": "2026-06-14" }              → INACTIVE (ngưng từ ngày đó)
     * Body: { "status": "ACTIVE" }                      → ACTIVE  (kích hoạt lại)
     */
    @PatchMapping("/{pitchId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<?> updatePitchStatus(
            @PathVariable UUID pitchId,
            @RequestBody PitchStatusRequestDTO request,
            Authentication authentication) {
        UUID requesterId = getUserIdFromAuth(authentication);
        if (requesterId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        // Reactivate
        if ("ACTIVE".equalsIgnoreCase(request.getStatus())) {
            pitchService.reactivatePitch(pitchId, requesterId, isAdmin);
            return ResponseEntity.ok(Map.of("message", "Sân đã được kích hoạt lại."));
        }

        // Deactivate — cần targetDate
        LocalDate targetDate = request.getTargetDate();
        if (targetDate == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "targetDate là bắt buộc khi ngưng sân."));
        }

        try {
            pitchService.deactivatePitch(pitchId, targetDate, requesterId, isAdmin);
            return ResponseEntity.ok(Map.of("message", "Sân đã được ngưng hoạt động từ " + targetDate + "."));
        } catch (PitchDeactivateBlockedException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Không thể ngưng sân");
            body.put("confirmedBookingCount", ex.getConfirmedBookingCount());
            body.put("earliestDeactivationDate", ex.getEarliestDeactivationDate().toString());
            return ResponseEntity.status(409).body(body);
        }
    }

    @GetMapping("/{pitchId}")
    // @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PitchResponseDTO> getPitchById(@PathVariable UUID pitchId) {
        PitchResponseDTO pitch = pitchService.getPitchById(pitchId);
        return ResponseEntity.ok(pitch);
    }

    @GetMapping("/{pitchId}/route")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getRouteToPitch(@PathVariable UUID pitchId,
                                             @RequestParam double fromLat,
                                             @RequestParam double fromLng) {
        PitchResponseDTO pitch = pitchService.getPitchById(pitchId);
        if (pitch.getLatitude() == null || pitch.getLongitude() == null) {
            return ResponseEntity.noContent().build();
        }
        return routingService.route(fromLat, fromLng, pitch.getLatitude(), pitch.getLongitude())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/admin/backfill-coordinates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Integer> backfillCoordinates() {
        return ResponseEntity.ok(pitchService.backfillPitchCoordinates());
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