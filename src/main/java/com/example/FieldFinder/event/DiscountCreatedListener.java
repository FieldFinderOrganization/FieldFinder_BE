package com.example.FieldFinder.event;

import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gửi thông báo "mã mới" cho đúng segment đã nhận mã — chạy SAU KHI transaction tạo mã
 * đã commit, trên thread riêng (@Async). Trước đây vòng lặp notify N user chạy đồng bộ
 * trong request tạo mã (mỗi user một transaction REQUIRES_NEW + một WS push) khiến API
 * treo quá 60s và FE timeout dù mã đã được lưu thành công.
 *
 * Gate: chỉ báo mã dùng được ngay (ACTIVE + đã tới ngày bắt đầu).
 * - pointCost == null: recipients = các dòng ví vừa bulk-assign (all-users hoặc tier >= minTier).
 * - pointCost != null: báo user đủ điểm để đổi.
 */
@Component
@RequiredArgsConstructor
public class DiscountCreatedListener {

    private final NotificationService notificationService;
    private final UserDiscountRepository userDiscountRepository;
    private final UserRepository userRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDiscountCreated(DiscountCreatedEvent event) {
        Discount saved = event.getDiscount();
        if (saved.getStatus() != Discount.DiscountStatus.ACTIVE) return;
        if (saved.getStartDate() != null && LocalDate.now().isBefore(saved.getStartDate())) return;

        List<UUID> recipients = (saved.getPointCost() == null)
                ? userDiscountRepository.findUserIdsByDiscountId(saved.getDiscountId())
                : userRepository.findUserIdsWithPointsAtLeast(saved.getPointCost());
        if (recipients == null || recipients.isEmpty()) return;

        String body = buildDiscountBody(saved);
        for (UUID userId : recipients) {
            if (userId == null) continue;
            notificationService.notify(userId, "DISCOUNT_NEW",
                    "Mã giảm giá mới 🎟️", body, "DISCOUNT", saved.getCode());
        }
    }

    private String buildDiscountBody(Discount d) {
        String value = d.getDiscountType() == Discount.DiscountType.PERCENTAGE
                ? ("giảm " + d.getValue().stripTrailingZeros().toPlainString() + "%")
                : ("giảm " + d.getValue().stripTrailingZeros().toPlainString() + "đ");
        if (d.getPointCost() != null) {
            return "Mã " + d.getCode() + " (" + value + ") — đổi bằng " + d.getPointCost() + " điểm.";
        }
        return "Mã " + d.getCode() + " " + value + " đã có trong ví của bạn.";
    }
}
