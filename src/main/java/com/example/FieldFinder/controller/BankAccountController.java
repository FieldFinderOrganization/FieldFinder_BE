package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.BankAccountLookupRequestDTO;
import com.example.FieldFinder.dto.req.BankAccountRequestDTO;
import com.example.FieldFinder.dto.res.BankAccountLookupResponseDTO;
import com.example.FieldFinder.dto.res.BankAccountResponseDTO;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.banklookup.BankLookupService;
import com.example.FieldFinder.service.banklookup.BankLookupService.BankLookupResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Quản lý tài khoản ngân hàng nhận hoàn tiền của người dùng. */
@RestController
@RequestMapping("/api/bank-accounts")
@PreAuthorize("isAuthenticated()")
public class BankAccountController {

    private final BankAccountService bankAccountService;
    private final UserRepository userRepository;
    private final BankLookupService bankLookupService;

    public BankAccountController(BankAccountService bankAccountService,
                                 UserRepository userRepository,
                                 BankLookupService bankLookupService) {
        this.bankAccountService = bankAccountService;
        this.userRepository = userRepository;
        this.bankLookupService = bankLookupService;
    }

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
                return userRepository.findByEmail(email).map(u -> u.getUserId()).orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        List<BankAccountResponseDTO> out = bankAccountService.listMine(userId).stream()
                .map(BankAccountResponseDTO::from).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/default")
    public ResponseEntity<?> getDefault(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        return bankAccountService.getDefault(userId)
                .<ResponseEntity<?>>map(b -> ResponseEntity.ok(BankAccountResponseDTO.from(b)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("hasAccount", false)));
    }

    /** Tra cứu tên chủ TK trước khi lưu (preview). FE hiện tên cho user xác nhận. */
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody BankAccountLookupRequestDTO dto, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        BankLookupResult r = bankLookupService.lookup(dto.bankBin(), dto.accountNumber());
        return ResponseEntity.ok(new BankAccountLookupResponseDTO(r.ok(), r.accountName(), r.message()));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody BankAccountRequestDTO dto, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        try {
            return ResponseEntity.ok(BankAccountResponseDTO.from(
                    bankAccountService.saveOrUpdate(userId, dto)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{bankAccountId}/default")
    public ResponseEntity<?> setDefault(@PathVariable UUID bankAccountId, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        return ResponseEntity.ok(BankAccountResponseDTO.from(
                bankAccountService.setDefault(userId, bankAccountId)));
    }

    @DeleteMapping("/{bankAccountId}")
    public ResponseEntity<?> delete(@PathVariable UUID bankAccountId, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        bankAccountService.delete(userId, bankAccountId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
    }
}
