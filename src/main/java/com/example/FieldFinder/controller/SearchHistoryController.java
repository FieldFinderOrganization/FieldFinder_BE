package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.SearchHistoryRequestDTO;
import com.example.FieldFinder.dto.res.SearchHistoryResponseDTO;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.SearchHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/search-history")
public class SearchHistoryController {

    private final SearchHistoryService searchHistoryService;
    private final RedisService redisService;

    public SearchHistoryController(SearchHistoryService searchHistoryService, RedisService redisService) {
        this.searchHistoryService = searchHistoryService;
        this.redisService = redisService;
    }

    private UUID currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập");
        }
        String email;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) email = ud.getUsername();
        else email = principal.toString();
        UUID id = redisService.getUserIdByEmail(email);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không xác định user");
        return id;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SearchHistoryResponseDTO>> getHistory(
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth) {
        return ResponseEntity.ok(searchHistoryService.getHistory(currentUserId(auth), limit));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SearchHistoryResponseDTO> upsert(
            @RequestBody SearchHistoryRequestDTO dto,
            Authentication auth) {
        return ResponseEntity.ok(searchHistoryService.upsert(currentUserId(auth), dto.getKeyword()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        searchHistoryService.delete(currentUserId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> clear(Authentication auth) {
        searchHistoryService.clear(currentUserId(auth));
        return ResponseEntity.noContent().build();
    }
}
