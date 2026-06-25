package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.entity.ShipperCodRemit;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ShipperCodRemitRepository;
import com.example.FieldFinder.service.impl.PayOSService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shipper NỘP LẠI tiền hàng thu hộ (COD) qua PayOS để xóa công nợ ví. KHÔNG tin webhook để giảm nợ:
 * khi nhận webhook báo PAID, gọi thẳng PayOS xác nhận trạng thái + số tiền rồi mới credit COD_REMIT
 * — chống webhook giả. Giảm nợ đúng-một-lần qua {@code markCreditedIfPending}. Mirror WalletTopupService.
 */
@Service
@RequiredArgsConstructor
public class ShipperCodRemitService {

    private final ShipperCodRemitRepository remitRepository;
    private final PayOSService payOSService;
    private final ShipperWalletService walletService;

    @Value("${front_end_url}")
    private String frontEndUrl;

    /** Nộp tối thiểu mỗi lệnh (tránh phí cổng > tiền lẻ). */
    @Value("${shipper.wallet.min-remit:10000}")
    private BigDecimal minRemit;

    /** Tạo link PayOS để shipper nộp tiền COD; lưu lệnh PENDING, trả về cho FE hiện QR. */
    @Transactional
    public ShipperCodRemit createRemit(User shipper, BigDecimal amount) {
        if (shipper == null) throw new IllegalArgumentException("Shipper required");
        if (amount == null || amount.compareTo(minRemit) < 0) {
            throw new IllegalArgumentException("Số tiền nộp tối thiểu là " + minRemit.toBigInteger() + "đ.");
        }
        // Nợ hiện tại = phần âm của số dư ví. Không cho nộp quá nợ (tránh đẩy ví thành dương rút được).
        BigDecimal balance = walletService.getBalance(shipper.getUserId());
        BigDecimal debt = balance.signum() < 0 ? balance.abs() : BigDecimal.ZERO;
        if (debt.signum() <= 0) {
            throw new IllegalArgumentException("Ví không có công nợ COD để nộp.");
        }
        if (amount.compareTo(debt) > 0) {
            throw new IllegalArgumentException("Số tiền nộp vượt quá công nợ COD (" + debt.toBigInteger() + "đ).");
        }

        long orderCode = generateOrderCode();
        PayOSService.PaymentResult result = payOSService.createPayment(
                amount,
                (int) orderCode,
                "Nop tien COD shipper",
                frontEndUrl + "/cod-remit-success",
                frontEndUrl + "/cod-remit-cancel");

        ShipperCodRemit remit = ShipperCodRemit.builder()
                .shipper(shipper)
                .amount(amount)
                .orderCode(orderCode)
                .transactionId(result.paymentLinkId())
                .checkoutUrl(result.checkoutUrl())
                .qrCode(result.qrCode())
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        return remitRepository.save(remit);
    }

    /**
     * Webhook báo có giao dịch cho {@code transactionId}. Nếu là 1 lệnh nộp COD: xác nhận server-side
     * với PayOS, giảm nợ đúng-một-lần, trả {@code true} (đã xử lý). Trả {@code false} nếu không phải.
     */
    @Transactional
    public boolean handlePaidWebhook(String transactionId) {
        if (transactionId == null) return false;
        ShipperCodRemit remit = remitRepository.findByTransactionId(transactionId).orElse(null);
        if (remit == null) return false; // không phải nộp COD
        confirmAndCredit(remit);
        return true;
    }

    /**
     * FE poll trạng thái 1 lệnh nộp. Tự xác nhận với PayOS + giảm nợ nếu đã trả.
     * Trả "CREDITED" | "PENDING" | "NOT_FOUND" | "FORBIDDEN".
     */
    @Transactional
    public String pollStatus(UUID remitId, UUID shipperId) {
        ShipperCodRemit r = remitRepository.findById(remitId).orElse(null);
        if (r == null) return "NOT_FOUND";
        if (r.getShipper() == null || !r.getShipper().getUserId().equals(shipperId)) {
            return "FORBIDDEN";
        }
        return confirmAndCredit(r) ? "CREDITED" : "PENDING";
    }

    /**
     * Xác nhận server-side với PayOS rồi credit COD_REMIT đúng-một-lần. Trả true nếu đã credit
     * (lúc này hoặc trước đó). KHÔNG tin webhook/poll — luôn đọc trạng thái thật từ PayOS.
     */
    private boolean confirmAndCredit(ShipperCodRemit remit) {
        if (!"PENDING".equals(remit.getStatus())) {
            return "CREDITED".equals(remit.getStatus());
        }
        PayOSService.PaymentInfo info = payOSService.getPaymentInfo(remit.getTransactionId());
        if (info == null || !info.isPaid()) return false;          // chưa trả thật → không credit
        if (BigDecimal.valueOf(info.amountPaid()).compareTo(remit.getAmount()) < 0) {
            return false;                                          // trả thiếu → không credit
        }
        // Khóa chuyển trạng thái nguyên tử: chỉ winner mới giảm nợ (chống webhook+poll trùng).
        int won = remitRepository.markCreditedIfPending(remit.getRemitId(), LocalDateTime.now());
        if (won == 1) {
            walletService.credit(remit.getShipper(), ShipperWalletTxnType.COD_REMIT, remit.getAmount(),
                    "REMIT", remit.getRemitId().toString(), "Nộp lại tiền COD qua PayOS");
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
