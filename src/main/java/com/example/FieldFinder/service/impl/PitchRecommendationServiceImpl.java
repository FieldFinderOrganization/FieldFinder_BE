package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.SuggestedPitchesResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.PitchRecommendationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PitchRecommendationServiceImpl implements PitchRecommendationService {

    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    public PitchRecommendationServiceImpl(PitchRepository pitchRepository, UserRepository userRepository) {
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SuggestedPitchesResponseDTO getSuggested(UUID pitchId, UUID userId, Double lat, Double lng, int limit) {
        log.info("[SUGGEST] getSuggested called - pitchId: {}, userId: {}, lat: {}, lng: {}, limit: {}", pitchId, userId, lat, lng, limit);
        int safeLimit = Math.max(1, Math.min(limit, 20));

        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (userId != null && user == null) {
            log.warn("[SUGGEST] User not found for ID: {}", userId);
        }

        Double effectiveLat = lat;
        Double effectiveLng = lng;
        if ((effectiveLat == null || effectiveLng == null) && user != null) {
            effectiveLat = user.getLatitude();
            effectiveLng = user.getLongitude();
            log.info("[SUGGEST] Coordinates missing. Fallback to user profile coordinates: lat={}, lng={}", effectiveLat, effectiveLng);
        }

        List<Pitch> nearby;
        if (effectiveLat != null && effectiveLng != null) {
            log.info("[SUGGEST] Finding nearby pitches relative to lat={}, lng={}, limit={}", effectiveLat, effectiveLng, safeLimit);
            nearby = pitchRepository.findNearbyPitches(pitchId, effectiveLat, effectiveLng, safeLimit);
            log.info("[SUGGEST] Found {} nearby pitches", nearby.size());
        } else if (user != null && user.getDistrict() != null && !user.getDistrict().isBlank()) {
            log.info("[SUGGEST] Finding pitches by district: '{}', limit={}", user.getDistrict(), safeLimit);
            nearby = pitchRepository.findByDistrictKeyword(pitchId, user.getDistrict(), safeLimit);
            log.info("[SUGGEST] Found {} pitches by district", nearby.size());
        } else {
            log.info("[SUGGEST] No coordinates or district available. Nearby list will be empty.");
            nearby = List.of();
        }

        log.info("[SUGGEST] Finding top-rated pitches (excluding pitchId: {}, limit={})", pitchId, safeLimit);
        List<Pitch> topRated = pitchRepository.findTopRatedPitches(pitchId, PageRequest.of(0, safeLimit));
        log.info("[SUGGEST] Found {} top-rated pitches", topRated.size());

        log.info("[SUGGEST] Finding visited pitches (excluding pitchId: {}, userId: {}, limit={})", pitchId, userId, safeLimit);
        List<Pitch> visited = userId == null ? List.of() : loadVisited(pitchId, userId, safeLimit);
        log.info("[SUGGEST] Found {} visited pitches", visited.size());

        SuggestedPitchesResponseDTO response = new SuggestedPitchesResponseDTO(
                toDtoList(nearby),
                toDtoList(topRated),
                toDtoList(visited)
        );
        log.info("[SUGGEST] Returning response: nearby_dto_count={}, top_rated_dto_count={}, visited_dto_count={}",
                response.getNearby().size(), response.getTopRated().size(), response.getVisited().size());
        return response;
    }

    private List<Pitch> loadVisited(UUID excludeId, UUID userId, int limit) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();

        if (mongoTemplate != null) {
            try {
                Query q = Query.query(
                        Criteria.where("userId").is(userId.toString())
                                .and("eventType").is("VIEW_PITCH")
                                .and("itemType").is("PITCH")
                ).with(Sort.by(Sort.Direction.DESC, "timestamp"));
                q.limit(50);
                List<InteractionLog> logs = mongoTemplate.find(q, InteractionLog.class);
                log.info("[SUGGEST-VISITED] Found {} view logs in Mongo for user {}", logs.size(), userId);
                for (InteractionLog l : logs) {
                    if (l.getItemId() == null) continue;
                    try {
                        UUID uuid = UUID.fromString(l.getItemId());
                        if (!uuid.equals(excludeId)) ids.add(uuid);
                    } catch (IllegalArgumentException ignored) {}
                    if (ids.size() >= limit * 2) break;
                }
            } catch (Exception e) {
                log.warn("[SUGGEST-VISITED] mongo query failed: {}", e.getMessage(), e);
            }
        } else {
            log.info("[SUGGEST-VISITED] mongoTemplate is null, skipping MongoDB view logs");
        }

        try {
            List<UUID> booked = pitchRepository.findBookedPitchIdsByUser(userId, excludeId);
            log.info("[SUGGEST-VISITED] Found {} booked pitch IDs for user {}", booked.size(), userId);
            ids.addAll(booked);
        } catch (Exception e) {
            log.warn("[SUGGEST-VISITED] booking query failed: {}", e.getMessage(), e);
        }

        if (ids.isEmpty()) return List.of();

        List<UUID> ordered = ids.stream().limit(limit).collect(Collectors.toList());
        Map<UUID, Pitch> byId = pitchRepository.findAllById(ordered).stream()
                .collect(Collectors.toMap(Pitch::getPitchId, p -> p));

        List<Pitch> result = ordered.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        log.info("[SUGGEST-VISITED] Loaded {} actual pitches from database for recommendation", result.size());
        return result;
    }

    private List<PitchResponseDTO> toDtoList(List<Pitch> pitches) {
        if (pitches == null) return List.of();
        return pitches.stream()
                .map(p -> {
                    try { return PitchResponseDTO.fromEntity(p); }
                    catch (Exception e) {
                        log.warn("[SUGGEST] Skipped converting pitch {} to DTO due to exception: {}", p.getPitchId(), e.getMessage(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
