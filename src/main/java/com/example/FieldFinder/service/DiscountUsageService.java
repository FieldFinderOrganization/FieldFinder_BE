package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.DiscountKind;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.DiscountUsage;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.DiscountUsageRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vòng đời lượt dùng voucher: ghi nhận khi checkout, hoàn lại khi hủy đơn/booking.
 * Hoàn = un-use UserDiscount + cộng lại quantity (PROMOTION) / cộng lại remainingValue (REFUND_CREDIT).
 * Chỉ phụ thuộc repository — tránh vòng phụ thuộc với Order/Booking/Discount service.
 */
@Service
@RequiredArgsConstructor
public class DiscountUsageService {

    private final DiscountUsageRepository usageRepository;
    private final UserDiscountRepository userDiscountRepository;
    private final DiscountRepository discountRepository;

    public void recordForOrder(UserDiscount userDiscount, Long orderId, BigDecimal amountDeducted) {
        usageRepository.save(DiscountUsage.builder()
                .userDiscount(userDiscount)
                .orderId(orderId)
                .amountDeducted(amountDeducted)
                .build());
    }

    public void recordForBooking(UserDiscount userDiscount, UUID bookingId, BigDecimal amountDeducted) {
        usageRepository.save(DiscountUsage.builder()
                .userDiscount(userDiscount)
                .bookingId(bookingId)
                .amountDeducted(amountDeducted)
                .build());
    }

    @Transactional
    public void revertForOrder(Long orderId) {
        revert(usageRepository.findActiveByOrderId(orderId));
    }

    @Transactional
    public void revertForBooking(UUID bookingId) {
        revert(usageRepository.findActiveByBookingId(bookingId));
    }

    /**
     * Hoàn voucher cho booking nhưng FORFEIT (không trả về ví) các voucher KM
     * (PROMOTION) — dùng khi khách hủy SÁT GIỜ: voucher khuyến mãi đã áp dụng bị
     * hủy hoàn toàn ở mọi mốc phạt. Mã hoàn tiền (REFUND_CREDIT) vẫn được hoàn lại số dư.
     */
    @Transactional
    public void revertForBookingExceptPromotion(UUID bookingId) {
        for (DiscountUsage usage : usageRepository.findActiveByBookingId(bookingId)) {
            Discount d = usage.getUserDiscount().getDiscount();
            if (d.getKind() == DiscountKind.PROMOTION) {
                // Forfeit: giữ nguyên đã dùng, không cộng lại lượt, không trả về ví.
                usage.setReverted(true);
                usageRepository.save(usage);
            } else {
                revertOne(usage); // REFUND_CREDIT: hoàn lại số dư như bình thường
            }
        }
    }

    private void revert(List<DiscountUsage> usages) {
        for (DiscountUsage usage : usages) {
            revertOne(usage);
        }
    }

    private void revertOne(DiscountUsage usage) {
        UserDiscount ud = usage.getUserDiscount();
        Discount d = ud.getDiscount();

        if (d.getKind() == DiscountKind.REFUND_CREDIT) {
            BigDecimal restored = usage.getAmountDeducted() != null
                    ? usage.getAmountDeducted() : BigDecimal.ZERO;
            BigDecimal current = ud.getRemainingValue() != null
                    ? ud.getRemainingValue() : BigDecimal.ZERO;
            ud.setRemainingValue(current.add(restored));
            if (ud.getRemainingValue().signum() > 0) {
                ud.setUsed(false);
                ud.setUsedAt(null);
            }
        } else {
            ud.setUsed(false);
            ud.setUsedAt(null);
            discountRepository.incrementQuantity(d.getDiscountId());
        }
        userDiscountRepository.save(ud);

        usage.setReverted(true);
        usageRepository.save(usage);
    }
}
