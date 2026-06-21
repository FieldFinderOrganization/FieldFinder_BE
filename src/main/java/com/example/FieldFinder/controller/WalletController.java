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

    @GetMapping
    public ResponseEntity<?> myWallet(Authentication authentication) {
        Provider provider = resolveProvider(authentication);
        if (provider == null) return ResponseEntity.status(401).build();
        UUID pid = provider.getProviderId();
        return ResponseEntity.ok(WalletViewDTO.builder()
                .balance(walletService.getBalance(pid))
                .reserve(walletService.computeReserve(pid))
                .withdrawable(walletService.computeWithdrawable(pid))
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
