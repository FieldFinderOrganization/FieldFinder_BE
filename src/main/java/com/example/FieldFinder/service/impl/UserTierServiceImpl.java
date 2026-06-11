package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.UserTier;
import com.example.FieldFinder.dto.res.TierInfoResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.UserTierService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserTierServiceImpl implements UserTierService {

    /** Trạng thái đơn được tính là "đã chi tiêu" — nhất quán với AdminStatisticsController. */
    private static final List<OrderStatus> PAID_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.CONFIRMED, OrderStatus.DELIVERED);

    private static final int SPENDING_WINDOW_MONTHS = 12;

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final UserDiscountRepository userDiscountRepository;
    private final EmailService emailService;

    private LocalDateTime windowStart() {
        return LocalDateTime.now().minusMonths(SPENDING_WINDOW_MONTHS);
    }

    /** Tên các tier từ MEMBER tới maxTier (dùng cho IN (:tiers) của query gán voucher). */
    private static List<String> tierNamesUpTo(UserTier maxTier) {
        List<String> names = new ArrayList<>();
        for (UserTier t : UserTier.values()) {
            if (t.ordinal() <= maxTier.ordinal()) names.add(t.name());
        }
        return names;
    }

    @Override
    @Transactional
    public void recalcTier(UUID userId) {
        if (userId == null) return;
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        double spent = orderRepository.sumSpentByUserSince(userId, PAID_STATUSES, windowStart());
        UserTier oldTier = user.getEffectiveTier();
        UserTier newTier = UserTier.fromSpending(spent);

        user.setTotalSpent12m(spent);
        user.setTier(newTier);
        user.setTierUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        if (newTier.ordinal() > oldTier.ordinal()) {
            // 1 câu INSERT...SELECT: gán mọi mã ACTIVE còn hạn của các hạng <= hạng mới
            userDiscountRepository.bulkAssignEligibleDiscountsToUser(userId, tierNamesUpTo(newTier));

            final User upgradedUser = user;
            final UserTier finalTier = newTier;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendUpgradeEmailSafely(upgradedUser, finalTier);
                    }
                });
            } else {
                sendUpgradeEmailSafely(upgradedUser, finalTier);
            }
        }
    }

    private void sendUpgradeEmailSafely(User user, UserTier newTier) {
        try {
            emailService.sendTierUpgrade(user, newTier);
        } catch (Exception e) {
            System.err.println("Lỗi gửi email lên hạng cho user " + user.getEmail() + ": " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TierInfoResponseDTO getTierInfo(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Tính trực tiếp từ orders để luôn đúng (cache totalSpent12m chỉ dùng cho admin list)
        double spent = orderRepository.sumSpentByUserSince(userId, PAID_STATUSES, windowStart());
        UserTier tier = UserTier.fromSpending(spent);
        UserTier next = tier.next();

        if (next == null) {
            return TierInfoResponseDTO.builder()
                    .tier(tier.name())
                    .totalSpent12m(spent)
                    .progressPercent(100)
                    .build();
        }

        long lower = tier.getThreshold();
        long upper = next.getThreshold();
        int progress = (int) Math.min(100, Math.max(0,
                (spent - lower) * 100.0 / (upper - lower)));

        return TierInfoResponseDTO.builder()
                .tier(tier.name())
                .totalSpent12m(spent)
                .nextTier(next.name())
                .nextTierThreshold(upper)
                .amountToNextTier(Math.max(0, upper - spent))
                .progressPercent(progress)
                .build();
    }

    /**
     * Job đêm: xử lý xuống hạng khi đơn cũ rớt khỏi cửa sổ 12 tháng + refresh cache chi tiêu.
     * Set-based: 1 query nhỏ tìm user lên hạng (hiếm — hook bỏ sót) xử lý riêng để có quà + email,
     * rồi 1 câu UPDATE...JOIN cho toàn bộ. Không loop per-user.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void recalcAllUsers() {
        LocalDateTime since = windowStart();
        long vip = UserTier.VIP.getThreshold();
        long gold = UserTier.GOLD.getThreshold();
        long diamond = UserTier.DIAMOND.getThreshold();

        List<String> upgradeIds = userRepository.findUserIdsNeedingTierUpgrade(since, vip, gold, diamond);
        for (String id : upgradeIds) {
            recalcTier(UUID.fromString(id));
        }

        int updated = userRepository.bulkRecalcTiers(since, vip, gold, diamond);
        System.out.println("[TierJob] Recalculated tiers: " + updated
                + " users updated, " + upgradeIds.size() + " upgrades.");
    }
}
