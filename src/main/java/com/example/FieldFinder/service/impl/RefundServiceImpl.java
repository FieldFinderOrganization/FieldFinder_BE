package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.DiscountKind;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.Enum.RefundStatus;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.RefundRequestRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.RefundService;
import lombok.RequiredArgsConstructor;
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
    private final EmailService emailService;

    @Override
    @Transactional
    public RefundRequest issueRefundCredit(User user,
                                           RefundSourceType sourceType,
                                           String sourceId,
                                           BigDecimal amount,
                                           String reason) {
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

        Discount discount = generateRefundDiscount(amount);
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
    public Discount generateRefundDiscount(BigDecimal amount) {
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
                .endDate(today.plusDays(DEFAULT_EXPIRY_DAYS))
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