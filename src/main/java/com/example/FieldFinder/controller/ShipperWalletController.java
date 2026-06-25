package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.ShipperWalletTransactionDTO;
import com.example.FieldFinder.dto.res.WalletViewDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ShipperWalletRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.PaymentPinService;
import com.example.FieldFinder.service.ShipperWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ví shipper: số dư, rút được, công nợ COD, sao kê, tự rút về TK ngân hàng. */
@RestController
@RequestMapping("/api/shippers/wallet")
@PreAuthorize("hasAnyRole('SHIPPER','ADMIN')")
@RequiredArgsConstructor
public class ShipperWalletController {

    private final ShipperWalletService walletService;
    private final UserRepository userRepository;
    private final ShipperWalletRepository walletRepository;
    private final BankAccountService bankAccountService;
    private final PaymentPinService pinService;
    private final com.example.FieldFinder.service.ShipperCodRemitService codRemitService;

    @GetMapping
    public ResponseEntity<?> myWallet(Authentication authentication) {
        User shipper = resolveShipper(authentication);
        if (shipper == null) return ResponseEntity.status(401).build();
        UUID id = shipper.getUserId();
        return ResponseEntity.ok(WalletViewDTO.builder()
                .balance(walletService.getBalance(id))
                .reserve(BigDecimal.ZERO)
                .withdrawable(walletService.computeWithdrawable(id))
                .minWithdraw(walletService.getMinWithdraw())
                .blocked(walletService.isBlocked(id))
                .negativeSince(walletService.getNegativeSince(id))
                .blockGraceDays(walletService.getBlockGraceDays())
                .build());
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> myTransactions(Authentication authentication) {
        User shipper = resolveShipper(authentication);
        if (shipper == null) return ResponseEntity.status(401).build();
        List<ShipperWalletTransactionDTO> out = walletService.listTransactions(shipper.getUserId())
                .stream().map(ShipperWalletTransactionDTO::fromEntity).toList();
        return ResponseEntity.ok(out);
    }

    /** Shipper tự RÚT tiền từ ví về TK (gác bằng PIN). amount ≤ rút được, TK phải APPROVED. */
    @PostMapping("/withdraw")
    @Transactional
    public ResponseEntity<?> withdraw(@RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "X-Payment-Pin", required = false) String pin,
                                      Authentication authentication) {
        User shipper = resolveShipper(authentication);
        if (shipper == null) return ResponseEntity.status(401).build();
        pinService.requireVerified(shipper.getUserId(), pin);

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Số tiền không hợp lệ."));
        }
        BigDecimal minWithdraw = walletService.getMinWithdraw();
        if (amount.compareTo(minWithdraw) < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Số tiền rút tối thiểu là " + minWithdraw + "đ."));
        }
        BigDecimal withdrawable = walletService.computeWithdrawable(shipper.getUserId());
        if (amount.signum() <= 0 || amount.compareTo(withdrawable) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Số tiền rút vượt quá số dư rút được (" + withdrawable + ")."));
        }
        var bank = bankAccountService.getDefault(shipper.getUserId())
                .filter(b -> b.getReviewStatus() == com.example.FieldFinder.Enum.BankReviewStatus.APPROVED)
                .orElse(null);
        if (bank == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Chưa có tài khoản ngân hàng đã duyệt để nhận tiền."));
        }
        var wtx = walletService.createWithdrawal(shipper, amount, bank);
        return ResponseEntity.ok(Map.of("txnId", wtx.getTxnId().toString(), "status", wtx.getStatus().name()));
    }

    /** Shipper NỘP lại tiền hàng COD qua PayOS → trả link/QR; ví chỉ giảm nợ khi webhook + xác nhận server-side. */
    @PostMapping("/remit")
    public ResponseEntity<?> remit(@RequestBody Map<String, Object> body, Authentication authentication) {
        User shipper = resolveShipper(authentication);
        if (shipper == null) return ResponseEntity.status(401).build();

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Số tiền không hợp lệ."));
        }
        try {
            com.example.FieldFinder.entity.ShipperCodRemit r = codRemitService.createRemit(shipper, amount);
            Map<String, Object> out = new HashMap<>();
            out.put("remitId", r.getRemitId().toString());
            out.put("transactionId", r.getTransactionId());
            out.put("checkoutUrl", r.getCheckoutUrl());
            out.put("qrCode", r.getQrCode());
            out.put("amount", r.getAmount());
            out.put("status", r.getStatus());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** FE poll trạng thái 1 lệnh nộp COD (tự xác nhận PayOS + giảm nợ nếu đã trả). */
    @GetMapping("/remit/{remitId}/status")
    public ResponseEntity<?> remitStatus(@PathVariable UUID remitId, Authentication authentication) {
        User shipper = resolveShipper(authentication);
        if (shipper == null) return ResponseEntity.status(401).build();
        String status = codRemitService.pollStatus(remitId, shipper.getUserId());
        if ("NOT_FOUND".equals(status)) return ResponseEntity.notFound().build();
        if ("FORBIDDEN".equals(status)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(Map.of("status", status));
    }

    // ----- Admin: ví âm (công nợ COD shipper) -----

    @GetMapping("/admin/negative")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> negativeWallets() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.example.FieldFinder.entity.ShipperWallet w : walletRepository.findAllNegative()) {
            String name = w.getShipper() != null ? w.getShipper().getName() : "Shipper";
            Map<String, Object> m = new HashMap<>();
            m.put("shipperId", w.getShipper() != null ? w.getShipper().getUserId().toString() : null);
            m.put("shipperName", name);
            m.put("balance", w.getBalance());
            m.put("negativeSince", w.getNegativeSince() != null ? w.getNegativeSince().toString() : null);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Admin XÓA NỢ (đưa ví về 0) — shipper đã nộp tiền ngoài hệ thống / được miễn. */
    @PostMapping("/admin/{shipperId}/waive")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> waive(@PathVariable UUID shipperId,
                                   @RequestParam(required = false) String note) {
        User shipper = userRepository.findById(shipperId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy shipper!"));
        BigDecimal balance = walletService.getBalance(shipperId);
        if (balance.signum() < 0) {
            walletService.credit(shipper, com.example.FieldFinder.Enum.ShipperWalletTxnType.ADJUSTMENT,
                    balance.abs(), "ADMIN_WAIVE", shipperId.toString() + ":" + System.currentTimeMillis(),
                    note != null ? note : "Admin xóa công nợ ví shipper");
        }
        return ResponseEntity.ok(Map.of("message", "Đã xử lý công nợ ví về 0."));
    }

    /** Admin xem sao kê ví của 1 shipper (audit/tranh chấp công nợ). */
    @GetMapping("/admin/{shipperId}/transactions")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> shipperTransactions(@PathVariable UUID shipperId) {
        List<ShipperWalletTransactionDTO> out = walletService.listTransactions(shipperId)
                .stream().map(ShipperWalletTransactionDTO::fromEntity).toList();
        return ResponseEntity.ok(out);
    }

    private User resolveShipper(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails ud) email = ud.getUsername();
            else if (principal instanceof String s) email = s;
            if (email == null) return null;
            return userRepository.findByEmail(email).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
