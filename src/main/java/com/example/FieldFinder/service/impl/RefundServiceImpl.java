package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.DiscountKind;
import com.example.FieldFinder.Enum.RefundMethod;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.Enum.RefundStatus;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.BankAccountRepository;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.RefundRequestRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.service.banklookup.BankLookupService;
import com.example.FieldFinder.service.banklookup.BankLookupService.BankLookupResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankLookupService bankLookupService;
    private final EmailService emailService;

    @Value("${refund.payout.deadline-hours:24}")
    private long payoutDeadlineHours;

    @Override
    @Transactional
    public RefundRequest issueRefundCredit(User user,
                                           RefundSourceType sourceType,
                                           String sourceId,
                                           BigDecimal amount,
                                           String reason) {
        return issueRefundCredit(user, sourceType, sourceId, amount, reason, null);
    }

    @Override
    @Transactional
    public RefundRequest issueRefundCredit(User user,
                                           RefundSourceType sourceType,
                                           String sourceId,
                                           BigDecimal amount,
                                           String reason,
                                           java.util.UUID restrictProviderId) {
        return issueRefundCredit(user, sourceType, sourceId, amount, reason,
                restrictProviderId, DEFAULT_EXPIRY_DAYS);
    }

    @Override
    @Transactional
    public RefundRequest issueRefundCredit(User user,
                                           RefundSourceType sourceType,
                                           String sourceId,
                                           BigDecimal amount,
                                           String reason,
                                           java.util.UUID restrictProviderId,
                                           int expiryDays) {
        if (user == null) throw new IllegalArgumentException("User required");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }

        // Idempotency: chặn refund trùng nguồn
        refundRequestRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .ifPresent(r -> {
                    throw new RuntimeException("Refund đã tồn tại cho " + sourceType + " #" + sourceId);
                });

        RefundRequest req = RefundRequest.builder()
                .user(user)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .amount(amount)
                .reason(reason)
                .status(RefundStatus.REQUESTED)
                .createdAt(LocalDateTime.now())
                .build();
        req = refundRequestRepository.save(req);

        Discount discount = generateRefundDiscount(amount, expiryDays);
        if (restrictProviderId != null) {
            discount.setRestrictProviderId(restrictProviderId);
            discount.setDescription("Mã hoàn tiền — chỉ áp dụng cho sân của chủ sân đã phát hành");
        }
        discount = discountRepository.save(discount);

        UserDiscount userDiscount = UserDiscount.builder()
                .user(user)
                .discount(discount)
                .isUsed(false)
                .savedAt(LocalDateTime.now())
                .remainingValue(amount)
                .build();
        userDiscountRepository.save(userDiscount);

        req.setIssuedDiscount(discount);
        req.setStatus(RefundStatus.ISSUED);
        req.setProcessedAt(LocalDateTime.now());
        RefundRequest saved = refundRequestRepository.save(req);

        // Gửi email sau khi commit để tránh email gửi nhưng tx rollback
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        emailService.sendRefundCodeIssued(saved);
                    } catch (Exception e) {
                        System.err.println("Lỗi gửi email mã hoàn tiền: " + e.getMessage());
                    }
                }
            });
        } else {
            try {
                emailService.sendRefundCodeIssued(saved);
            } catch (Exception e) {
                System.err.println("Lỗi gửi email mã hoàn tiền: " + e.getMessage());
            }
        }

        return saved;
    }

    @Override
    @Transactional
    public RefundRequest issueCashRefund(User user,
                                         RefundSourceType sourceType,
                                         String sourceId,
                                         BigDecimal amount,
                                         String reason,
                                         BankAccount bankAccount) {
        if (user == null) throw new IllegalArgumentException("User required");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (bankAccount == null) throw new IllegalArgumentException("Bank account required for cash refund");

        // Idempotency: chặn refund trùng nguồn (voucher hoặc cash)
        refundRequestRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .ifPresent(r -> {
                    throw new RuntimeException("Refund đã tồn tại cho " + sourceType + " #" + sourceId);
                });

        // GATE: chỉ chi tiền mặt khi TK đã xác thực là THẬT. TK chưa verify ⇒
        // tra cứu lại 1 lần; vẫn không xác thực được ⇒ KHÔNG chi tiền mặt, phát voucher.
        boolean canCash = bankAccount.isVerified();
        String gateNote = null;
        if (!canCash) {
            BankLookupResult lk = bankLookupService.lookup(
                    bankAccount.getBankBin(), bankAccount.getAccountNumber());
            if (lk.ok()) {
                bankAccount.setVerified(true);
                bankAccountRepository.save(bankAccount);
                canCash = true;
            } else {
                gateNote = "TK ngân hàng chưa xác thực được"
                        + (lk.message() != null ? " (" + lk.message() + ")" : "")
                        + " — đã phát voucher thay tiền mặt.";
            }
        }

        RefundRequest req = RefundRequest.builder()
                .user(user)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .amount(amount)
                .reason(reason)
                .refundMethod(RefundMethod.CASH)
                .status(RefundStatus.PAYOUT_PENDING)
                .bankBin(bankAccount.getBankBin())
                .bankAccountNumber(bankAccount.getAccountNumber())
                .bankAccountName(bankAccount.getAccountName())
                .deadlineAt(LocalDateTime.now().plusHours(payoutDeadlineHours))
                .attemptCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        req = refundRequestRepository.save(req);

        // referenceId PayOS ổn định, duy nhất, ≤64 ký tự — dùng làm khóa idempotency phía PayOS
        req.setPayosReferenceId("RF" + req.getRefundId().toString().replace("-", ""));
        req = refundRequestRepository.save(req);

        // TK không xác thực được ⇒ chuyển sang voucher ngay, không bao giờ chi tới TK chưa verify
        if (!canCash) {
            return fallbackToVoucher(req, gateNote);
        }
        return req;
    }

    @Override
    @Transactional
    public RefundRequest fallbackToVoucher(RefundRequest existing, String note) {
        if (existing == null) throw new IllegalArgumentException("Refund required");

        Discount discount = generateRefundDiscount(existing.getAmount());
        discount = discountRepository.save(discount);

        UserDiscount userDiscount = UserDiscount.builder()
                .user(existing.getUser())
                .discount(discount)
                .isUsed(false)
                .savedAt(LocalDateTime.now())
                .remainingValue(existing.getAmount())
                .build();
        userDiscountRepository.save(userDiscount);

        existing.setIssuedDiscount(discount);
        existing.setRefundMethod(RefundMethod.VOUCHER);
        existing.setStatus(RefundStatus.ISSUED);
        existing.setFailureReason(note);
        existing.setProcessedAt(LocalDateTime.now());
        RefundRequest saved = refundRequestRepository.save(existing);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        emailService.sendRefundCodeIssued(saved);
                    } catch (Exception e) {
                        System.err.println("Lỗi gửi email voucher bù: " + e.getMessage());
                    }
                }
            });
        } else {
            try {
                emailService.sendRefundCodeIssued(saved);
            } catch (Exception e) {
                System.err.println("Lỗi gửi email voucher bù: " + e.getMessage());
            }
        }
        return saved;
    }

    @Override
    public Discount generateRefundDiscount(BigDecimal amount) {
        return generateRefundDiscount(amount, DEFAULT_EXPIRY_DAYS);
    }

    @Override
    public Discount generateRefundDiscount(BigDecimal amount, int expiryDays) {
        String code = generateUniqueCode();
        LocalDate today = LocalDate.now();
        return Discount.builder()
                .code(code)
                .description("Mã hoàn tiền tự động (REFUND_CREDIT)")
                .discountType(Discount.DiscountType.FIXED_AMOUNT)
                .value(amount)
                .scope(Discount.DiscountScope.GLOBAL)
                .quantity(1)
                .startDate(today)
                .endDate(today.plusDays(expiryDays))
                .status(Discount.DiscountStatus.ACTIVE)
                .kind(DiscountKind.REFUND_CREDIT)
                .build();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_PREFIX);
            for (int i = 0; i < 10; i++) {
                sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!discountRepository.existsByCode(code)) return code;
        }
        throw new RuntimeException("Không sinh được mã hoàn tiền duy nhất");
    }

    @Override
    public Optional<RefundRequest> findBySource(RefundSourceType type, String sourceId) {
        return refundRequestRepository.findBySourceTypeAndSourceId(type, sourceId);
    }

    @Scheduled(fixedRate = 3_600_000L)
    @Transactional
    public void expireRefundCodes() {
        LocalDate today = LocalDate.now();
        List<Discount> all = discountRepository.findAll();
        int count = 0;
        for (Discount d : all) {
            if (d.getKind() == DiscountKind.REFUND_CREDIT
                    && d.getStatus() == Discount.DiscountStatus.ACTIVE
                    && d.getEndDate() != null
                    && d.getEndDate().isBefore(today)) {
                d.setStatus(Discount.DiscountStatus.EXPIRED);
                discountRepository.save(d);
                count++;
            }
        }
        if (count > 0) System.out.println("⏳ Đã hết hạn " + count + " mã hoàn tiền.");
    }
}