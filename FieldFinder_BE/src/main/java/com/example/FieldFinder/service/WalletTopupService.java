package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.WalletTopup;
import com.example.FieldFinder.repository.WalletTopupRepository;
import com.example.FieldFinder.service.impl.PayOSService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Nạp tiền vào ví chủ sân qua PayOS. KHÔNG tin webhook để cộng tiền: trước khi cộng ví,
 * gọi thẳng PayOS xác nhận trạng thái + số tiền — chống webhook giả mạo (nạp ví dẫn tới
 * tiền thật rút ra). Cộng đúng-một-lần qua {@code markCreditedIfPending}. Vừa nhận webhook
 * vừa cho FE poll xác nhận → không phụ thuộc 1 kênh duy nhất.
 */
@Service
@RequiredArgsConstructor
public class WalletTopupService {

    private final WalletTopupRepository topupRepository;
    private final PayOSService payOSService;
    private final WalletService walletService;

    @Value("${front_end_url}")
    private String frontEndUrl;

    @Value("${provider.wallet.min-topup:10000}")
    private BigDecimal minTopup;

    @Value("${provider.wallet.max-topup:50000000}")
    private BigDecimal maxTopup;

    public BigDecimal getMinTopup() {
        return minTopup;
    }

    /** Tạo link PayOS để chủ sân nạp tiền; lưu lệnh PENDING, trả về cho FE hiện QR. */
    @Transactional
    public WalletTopup createTopup(Provider provider, BigDecimal amount) {
        if (provider == null) throw new IllegalArgumentException("Provider required");
        if (amount == null || amount.compareTo(minTopup) < 0) {
            throw new IllegalArgumentException("Số tiền nạp tối thiểu là " + minTopup.toBigInteger() + "đ.");
        }
        if (amount.compareTo(maxTopup) > 0) {
            throw new IllegalArgumentException("Số tiền nạp tối đa là " + maxTopup.toBigInteger() + "đ.");
        }

        long orderCode = generateOrderCode();
        PayOSService.PaymentResult result = payOSService.createPayment(
                amount,
                (int) orderCode,
                "Nap vi chu san",
                frontEndUrl + "/wallet-topup-success",
                frontEndUrl + "/wallet-topup-cancel");

        WalletTopup topup = WalletTopup.builder()
                .provider(provider)
                .amount(amount)
                .orderCode(orderCode)
                .transactionId(result.paymentLinkId())
                .checkoutUrl(result.checkoutUrl())
                .qrCode(result.qrCode())
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        return topupRepository.save(topup);
    }

    /**
     * Webhook báo có giao dịch cho {@code transactionId}. Nếu là lệnh nạp ví → xác nhận
     * + cộng ví, trả {@code true} (đã xử lý). Trả {@code false} nếu KHÔNG phải nạp ví →
     * caller chạy luồng thanh toán booking/order như cũ.
     */
    @Transactional
    public boolean handlePaidWebhook(String transactionId) {
        if (transactionId == null) return false;
        WalletTopup topup = topupRepository.findByTransactionId(transactionId).orElse(null);
        if (topup == null) return false; // không phải nạp ví
        confirmAndCredit(topup);
        return true;
    }

    /**
     * FE poll trạng thái 1 lệnh nạp. Tự xác nhận với PayOS + cộng ví nếu đã trả (không cần
     * chờ webhook). Trả "CREDITED" | "PENDING" | "NOT_FOUND" | "FORBIDDEN".
     */
    @Transactional
    public String pollStatus(UUID topupId, UUID providerId) {
        WalletTopup t = topupRepository.findById(topupId).orElse(null);
        if (t == null) return "NOT_FOUND";
        if (t.getProvider() == null || !t.getProvider().getProviderId().equals(providerId)) {
            return "FORBIDDEN";
        }
        return confirmAndCredit(t) ? "CREDITED" : "PENDING";
    }

    /**
     * Xác nhận server-side với PayOS rồi cộng ví đúng-một-lần. Trả true nếu đã cộng (lúc này
     * hoặc trước đó). KHÔNG tin webhook/poll — luôn đọc trạng thái thật từ PayOS.
     */
    private boolean confirmAndCredit(WalletTopup topup) {
        if (!"PENDING".equals(topup.getStatus())) {
            return "CREDITED".equals(topup.getStatus());
        }
        PayOSService.PaymentInfo info = payOSService.getPaymentInfo(topup.getTransactionId());
        if (info == null || !info.isPaid()) return false;          // chưa trả thật → không cộng
        if (BigDecimal.valueOf(info.amountPaid()).compareTo(topup.getAmount()) < 0) {
            return false;                                          // trả thiếu → không cộng
        }
        // Khóa chuyển trạng thái nguyên tử: chỉ winner mới cộng ví (chống webhook+poll trùng).
        int won = topupRepository.markCreditedIfPending(topup.getTopupId(), LocalDateTime.now());
        if (won == 1) {
            walletService.credit(topup.getProvider(), WalletTxnType.TOPUP, topup.getAmount(),
                    "TOPUP", topup.getTopupId().toString(), "Nạp tiền vào ví qua PayOS");
        }
        return true;
    }

    /** orderCode PayOS: int dương duy nhất (tránh trùng millisecond bằng nhiễu ngẫu nhiên). */
    private long generateOrderCode() {
        long base = System.currentTimeMillis() % 1_000_000_0000L;
        long noise = (long) (Math.random() * 1000);
        long code = (base * 1000 + noise) % Integer.MAX_VALUE;
        return code <= 0 ? -code + 1 : code;
    }
}
