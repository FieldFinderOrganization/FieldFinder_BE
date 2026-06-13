package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.PointTxType;
import com.example.FieldFinder.dto.res.PointInfoResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.PointTransaction;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.PointTransactionRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.PointService;
import com.example.FieldFinder.util.DiscountEligibilityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PointServiceImpl implements PointService {

    /** 10.000đ chi tiêu = 1 điểm. */
    private static final int POINT_UNIT_VND = 10_000;

    private final PointTransactionRepository pointTransactionRepository;
    private final UserRepository userRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;

    @Override
    @Transactional
    public void awardForOrder(Order order) {
        if (order == null || order.getUser() == null || order.getTotalAmount() == null) return;

        // Idempotent: shipper/admin có thể set DELIVERED nhiều lần
        if (pointTransactionRepository
                .findFirstByTypeAndRefOrderId(PointTxType.EARN_ORDER, order.getOrderId())
                .isPresent()) {
            return;
        }

        int pts = (int) Math.floor(order.getTotalAmount() / POINT_UNIT_VND);
        if (pts <= 0) return;

        // Cộng điểm qua entity managed (KHÔNG native UPDATE) — vì cùng transaction
        // updateOrderStatus còn gọi recalcTier save lại user; native UPDATE sẽ bị flush
        // của entity (points cũ) ghi đè. Set trên entity để cả 2 chỗ thấy giá trị mới.
        User user = order.getUser();
        user.setPoints(user.getEffectivePoints() + pts);
        userRepository.save(user);

        pointTransactionRepository.save(PointTransaction.builder()
                .user(user)
                .amount(pts)
                .type(PointTxType.EARN_ORDER)
                .refOrderId(order.getOrderId())
                .description("Tích điểm đơn hàng #" + order.getOrderId())
                .build());
    }

    @Override
    @Transactional
    public void revertForOrder(Long orderId) {
        PointTransaction earn = pointTransactionRepository
                .findFirstByTypeAndRefOrderId(PointTxType.EARN_ORDER, orderId)
                .orElse(null);
        if (earn == null || earn.isReverted()) return;

        earn.setReverted(true);
        pointTransactionRepository.save(earn);

        // Trừ điểm qua entity managed (cùng lý do với awardForOrder — tránh recalcTier ghi đè).
        // Cho phép số dư âm — chỉ khi admin hủy đơn DELIVERED sau khi user đã tiêu điểm.
        User user = earn.getUser();
        user.setPoints(user.getEffectivePoints() - earn.getAmount());
        userRepository.save(user);

        pointTransactionRepository.save(PointTransaction.builder()
                .user(user)
                .amount(-earn.getAmount())
                .type(PointTxType.REVERT_ORDER)
                .refOrderId(orderId)
                .description("Hoàn lại điểm do hủy đơn hàng #" + orderId)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public PointInfoResponseDTO getPointInfo(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return PointInfoResponseDTO.builder()
                .balance(user.getEffectivePoints())
                .transactions(pointTransactionRepository
                        .findTop50ByUser_UserIdOrderByCreatedAtDesc(userId).stream()
                        .map(PointInfoResponseDTO.PointTransactionDTO::fromEntity)
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    @Transactional
    public int redeem(UUID userId, UUID discountId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Discount discount = discountRepository.findById(discountId)
                .orElseThrow(() -> new RuntimeException("Discount not found"));

        if (discount.getPointCost() == null) {
            throw new RuntimeException("Mã này không đổi được bằng điểm");
        }
        if (!DiscountEligibilityUtil.isUsable(discount, LocalDate.now())) {
            throw new RuntimeException("Mã không còn hiệu lực hoặc đã hết lượt");
        }
        if (discount.getMinTier() != null
                && !user.getEffectiveTier().isAtLeast(discount.getMinTier())) {
            throw new RuntimeException("Mã chỉ dành cho hạng "
                    + discount.getMinTier().name() + " trở lên");
        }
        if (userDiscountRepository.existsByUserAndDiscount(user, discount)) {
            throw new RuntimeException("Bạn đã đổi mã này rồi");
        }

        int cost = discount.getPointCost();
        // Guard số dư trong cùng câu UPDATE — race-safe, 0 row = không đủ điểm
        if (userRepository.deductPointsIfEnough(userId, cost) == 0) {
            throw new RuntimeException("Không đủ điểm (cần " + cost + " điểm)");
        }

        pointTransactionRepository.save(PointTransaction.builder()
                .user(user)
                .amount(-cost)
                .type(PointTxType.REDEEM_VOUCHER)
                .refDiscountId(discountId)
                .description("Đổi voucher " + discount.getCode())
                .build());

        // Đưa mã vào ví — KHÔNG trừ quantity (quantity = tổng lượt dùng, trừ lúc checkout)
        userDiscountRepository.save(UserDiscount.builder()
                .user(user)
                .discount(discount)
                .isUsed(false)
                .savedAt(LocalDateTime.now())
                .build());

        return user.getEffectivePoints() - cost;
    }
}
