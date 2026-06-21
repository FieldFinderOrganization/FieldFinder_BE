package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.WalletTransactionDTO;
import com.example.FieldFinder.dto.res.WalletViewDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Ví chủ sân: số dư, reserve, rút được, sao kê. */
@RestController
@RequestMapping("/api/providers/wallet")
@PreAuthorize("hasAnyRole('PROVIDER','OWNER','ADMIN')")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final ProviderRepository providerRepository;
    private final UserRepository userRepository;
    private final com.example.FieldFinder.repository.ProviderWalletRepository walletRepository;
    private final com.example.FieldFinder.service.BankAccountService bankAccountService;
    private final com.example.FieldFinder.service.PaymentPinService pinService;

    /** Chủ sân tự RÚT tiền từ ví về TK (gác bằng PIN). amount ≤ rút được, TK phải APPROVED. */
    @org.springframework.web.bind.annotation.PostMapping("/withdraw")
    @Transactional
    public ResponseEntity<?> withdraw(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body,
                                      @org.springframework.web.bind.annotation.RequestHeader(value = "X-Payment-Pin", required = false) String pin,
                                      Authentication authentication) {
        Provider provider = resolveProvider(authentication);
        if (provider == null || provider.getUser() == null) return ResponseEntity.status(401).build();
        pinService.requireVerified(provider.getUser().getUserId(), pin);

        java.math.BigDecimal amount;
        try {
            amount = new java.math.BigDecimal(String.valueOf(body.get("amount")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Số tiền không hợp lệ."));
        }
        java.math.BigDecimal minWithdraw = walletService.getMinWithdraw();
        if (amount.compareTo(minWithdraw) < 0) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "Số tiền rút tối thiểu là " + minWithdraw + "đ."));
        }
        java.math.BigDecimal withdrawable = walletService.computeWithdrawable(provider.getProviderId());
        if (amount.signum() <= 0 || amount.compareTo(withdrawable) > 0) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "Số tiền rút vượt quá số dư rút được (" + withdrawable + ")."));
        }
        var bank = bankAccountService.getDefault(provider.getUser().getUserId())
                .filter(b -> b.getReviewStatus() == com.example.FieldFinder.Enum.BankReviewStatus.APPROVED)
                .orElse(null);
        if (bank == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "Chưa có tài khoản ngân hàng đã duyệt để nhận tiền."));
        }
        var wtx = walletService.createWithdrawal(provider, amount, bank);
        return ResponseEntity.ok(java.util.Map.of("txnId", wtx.getTxnId().toString(), "status", wtx.getStatus().name()));
    }

    // ----- Admin: ví âm (nợ chủ sân) -----

    @GetMapping("/admin/negative")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> negativeWallets() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.example.FieldFinder.entity.ProviderWallet w : walletRepository.findAllNegative()) {
            String name = (w.getProvider() != null && w.getProvider().getUser() != null)
                    ? w.getProvider().getUser().getName() : "Chủ sân";
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("providerId", w.getProvider() != null ? w.getProvider().getProviderId().toString() : null);
            m.put("providerName", name);
            m.put("balance", w.getBalance());
            m.put("negativeSince", w.getNegativeSince() != null ? w.getNegativeSince().toString() : null);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Admin XÓA NỢ (đưa ví về 0) — chủ sân đã trả ngoài hệ thống / được miễn. */
    @org.springframework.web.bind.annotation.PostMapping("/admin/{providerId}/waive")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> waive(@org.springframework.web.bind.annotation.PathVariable UUID providerId,
                                   @org.springframework.web.bind.annotation.RequestParam(required = false) String note) {
        Provider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy chủ sân!"));
        java.math.BigDecimal balance = walletService.getBalance(providerId);
        if (balance.signum() < 0) {
            walletService.credit(provider, com.example.FieldFinder.Enum.WalletTxnType.ADJUSTMENT,
                    balance.abs(), "ADMIN_WAIVE", providerId.toString() + ":" + System.currentTimeMillis(),
                    note != null ? note : "Admin xóa nợ ví");
        }
        return ResponseEntity.ok(java.util.Map.of("message", "Đã xử lý nợ ví về 0."));
    }

    /** Admin xem sao kê ví của 1 chủ sân (audit/tranh chấp nợ). */
    @GetMapping("/admin/{providerId}/transactions")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> providerTransactions(@org.springframework.web.bind.annotation.PathVariable UUID providerId) {
        List<WalletTransactionDTO> out = walletService.listTransactions(providerId)
                .stream().map(WalletTransactionDTO::fromEntity).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<?> myWallet(Authentication authentication) {
        Provider provider = resolveProvider(authentication);
        if (provider == null) return ResponseEntity.status(401).build();
        UUID pid = provider.getProviderId();
        return ResponseEntity.ok(WalletViewDTO.builder()
                .balance(walletService.getBalance(pid))
                .reserve(walletService.computeReserve(pid))
                .withdrawable(walletService.computeWithdrawable(pid))
                .minWithdraw(walletService.getMinWithdraw())
                .blocked(walletService.isBlocked(pid))
                .negativeSince(walletService.getNegativeSince(pid))
                .blockGraceDays(walletService.getBlockGraceDays())
                .build());
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> myTransactions(Authentication authentication) {
        Provider provider = resolveProvider(authentication);
        if (provider == null) return ResponseEntity.status(401).build();
        List<WalletTransactionDTO> out = walletService.listTransactions(provider.getProviderId())
                .stream().map(WalletTransactionDTO::fromEntity).toList();
        return ResponseEntity.ok(out);
    }

    private Provider resolveProvider(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails ud) email = ud.getUsername();
            else if (principal instanceof String s) email = s;
            if (email == null) return null;
            UUID userId = userRepository.findByEmail(email).map(u -> u.getUserId()).orElse(null);
            if (userId == null) return null;
            return providerRepository.findByUser_UserId(userId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
