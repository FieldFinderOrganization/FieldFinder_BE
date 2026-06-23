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
    private final com.example.FieldFinder.service.PaymentPinService pinService;

    public BankAccountController(BankAccountService bankAccountService,
                                 UserRepository userRepository,
                                 BankLookupService bankLookupService,
                                 com.example.FieldFinder.service.PaymentPinService pinService) {
        this.bankAccountService = bankAccountService;
        this.userRepository = userRepository;
        this.bankLookupService = bankLookupService;
        this.pinService = pinService;
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
    public ResponseEntity<?> save(@RequestBody BankAccountRequestDTO dto,
                                  @RequestHeader(value = "X-Payment-Pin", required = false) String pin,
                                  Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        pinService.requireVerified(userId, pin); // gác bằng PIN: chưa có ⇒ 428 PIN_REQUIRED; sai ⇒ 400/423
        try {
            return ResponseEntity.ok(BankAccountResponseDTO.from(
                    bankAccountService.saveOrUpdate(userId, dto)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{bankAccountId}/default")
    public ResponseEntity<?> setDefault(@PathVariable UUID bankAccountId,
                                        @RequestHeader(value = "X-Payment-Pin", required = false) String pin,
                                        Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        pinService.requireVerified(userId, pin);
        return ResponseEntity.ok(BankAccountResponseDTO.from(
                bankAccountService.setDefault(userId, bankAccountId)));
    }

    @DeleteMapping("/{bankAccountId}")
    public ResponseEntity<?> delete(@PathVariable UUID bankAccountId,
                                    @RequestHeader(value = "X-Payment-Pin", required = false) String pin,
                                    Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return unauthorized();
        pinService.requireVerified(userId, pin); // gác bằng PIN: đồng bộ với thêm/sửa/mặc-định
        bankAccountService.delete(userId, bankAccountId);
        return ResponseEntity.noContent().build();
    }

    // ----- Admin: duyệt TK tên lệch hồ sơ -----

    @GetMapping("/admin/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> pendingReview() {
        List<BankAccountResponseDTO> out = bankAccountService.listPendingReview().stream()
                .map(BankAccountResponseDTO::from).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/admin/{bankAccountId}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> review(@PathVariable UUID bankAccountId,
                                    @RequestParam boolean approve,
                                    @RequestParam(required = false) String note) {
        return ResponseEntity.ok(BankAccountResponseDTO.from(
                bankAccountService.review(bankAccountId, approve, note)));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
    }
}
