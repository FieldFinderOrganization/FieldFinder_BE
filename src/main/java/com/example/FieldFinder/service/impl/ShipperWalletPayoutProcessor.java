package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.ShipperWallet;
import com.example.FieldFinder.entity.ShipperWalletTransaction;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ShipperWalletRepository;
import com.example.FieldFinder.repository.ShipperWalletTransactionRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.ShipperWalletService;
import com.example.FieldFinder.service.payout.PayoutCommand;
import com.example.FieldFinder.service.payout.PayoutProvider;
import com.example.FieldFinder.service.payout.PayoutResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Tự động RÚT số dư rút được của ví shipper về TK ngân hàng qua PayOS — job nền, không chặn luồng chính.
 * Tái dùng cùng {@link PayoutProvider} với ví chủ sân (cùng TK chi). Quy trình mỗi vòng:
 * (1) tạo lệnh rút cho ví có withdrawable>0, (2) đẩy lệnh PENDING, (3) poll PROCESSING.
 * Thất bại vĩnh viễn ⇒ hoàn lại số dư ví. Idempotent + giới hạn số lần thử.
 */
@Component
@RequiredArgsConstructor
public class ShipperWalletPayoutProcessor {

    private final ShipperWalletRepository walletRepository;
    private final ShipperWalletTransactionRepository txRepository;
    private final ShipperWalletService walletService;
    private final BankAccountService bankAccountService;
    private final PayoutProvider payoutProvider;
    private final NotificationService notificationService;

    @Value("${shipper.wallet.withdraw-max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${shipper.wallet.auto-payout-interval-ms:600000}")
    @Transactional
    public void autoCreateWithdrawals() {
        for (ShipperWallet wallet : walletRepository.findAllPositive()) {
            User shipper = wallet.getShipper();
            if (shipper == null) continue;

            // Đã có lệnh rút dở ⇒ chờ xong rồi tính tiếp.
            if (txRepository.existsByShipper_UserIdAndStatusIn(
                    shipper.getUserId(), List.of(WalletTxnStatus.PENDING, WalletTxnStatus.PROCESSING))) {
                continue;
            }
            BigDecimal withdrawable = walletService.computeWithdrawable(shipper.getUserId());
            // Dưới sàn rút tối thiểu ⇒ giữ trong ví, gom đủ rồi rút (tránh phí payout > tiền lẻ).
            if (withdrawable.compareTo(walletService.getMinWithdraw()) < 0) continue;

            Optional<BankAccount> bank = bankAccountService.getDefault(shipper.getUserId());
            // Chưa liên kết TK / TK chưa được DUYỆT (tên lệch hồ sơ) ⇒ giữ trong ví, không chi.
            if (bank.isEmpty()
                    || bank.get().getReviewStatus() != com.example.FieldFinder.Enum.BankReviewStatus.APPROVED) {
                continue;
            }

            walletService.createWithdrawal(shipper, withdrawable, bank.get());
        }
    }

    @Scheduled(fixedDelayString = "${shipper.wallet.dispatch-interval-ms:30000}")
    @Transactional
    public void dispatchPending() {
        List<ShipperWalletTransaction> pending = txRepository.findByStatusOrderByCreatedAtAsc(WalletTxnStatus.PENDING);
        if (pending.isEmpty()) return;
        Optional<BigDecimal> balance = payoutProvider.getBalance();

        for (ShipperWalletTransaction wtx : pending) {
            BigDecimal amount = wtx.getAmount().abs();
            if (balance.isPresent() && balance.get().compareTo(amount) < 0) {
                System.err.println("[ShipperPayout] Số dư TK chi không đủ cho rút " + wtx.getTxnId()
                        + " (cần " + amount + ") — chờ nạp.");
                continue;
            }
            wtx.setAttemptCount(wtx.getAttemptCount() + 1);
            wtx.setLastAttemptAt(LocalDateTime.now());

            PayoutResult res = payoutProvider.disburse(new PayoutCommand(
                    wtx.getPayosReferenceId(),
                    amount.longValueExact(),
                    "Rut tien vi shipper " + wtx.getTxnId(),
                    wtx.getBankBin(),
                    wtx.getBankAccountNumber()));

            wtx.setPayosTxnState(res.providerState());
            wtx.setFailureReason(res.message());

            switch (res.state()) {
                case SUCCEEDED -> {
                    wtx.setPayosPayoutId(res.payoutId());
                    markSucceeded(wtx);
                }
                case PROCESSING -> {
                    wtx.setPayosPayoutId(res.payoutId());
                    wtx.setStatus(WalletTxnStatus.PROCESSING);
                    txRepository.save(wtx);
                }
                case FAILED, UNKNOWN -> failOrRetry(wtx, res.message());
            }
        }
    }

    @Scheduled(fixedDelayString = "${shipper.wallet.poll-interval-ms:45000}")
    @Transactional
    public void pollProcessing() {
        for (ShipperWalletTransaction wtx : txRepository.findByStatusOrderByCreatedAtAsc(WalletTxnStatus.PROCESSING)) {
            if (wtx.getPayosPayoutId() == null) continue;
            PayoutResult res = payoutProvider.getStatus(wtx.getPayosPayoutId());
            wtx.setPayosTxnState(res.providerState());
            switch (res.state()) {
                case SUCCEEDED -> markSucceeded(wtx);
                case FAILED -> {
                    wtx.setFailureReason(res.message());
                    walletService.reverseFailedWithdrawal(wtx); // hoàn số dư
                    alertAdmin(wtx, "Rút FAILED khi poll: " + res.message());
                }
                case PROCESSING, UNKNOWN -> { /* chờ vòng sau */ }
            }
        }
    }

    private void failOrRetry(ShipperWalletTransaction wtx, String reason) {
        if (wtx.getAttemptCount() >= maxAttempts) {
            walletService.reverseFailedWithdrawal(wtx); // quá số lần ⇒ hoàn số dư, đóng lệnh
            alertAdmin(wtx, "Rút FAILED sau " + wtx.getAttemptCount() + " lần: " + reason);
        } else {
            txRepository.save(wtx); // còn lượt ⇒ giữ PENDING, thử lại
        }
    }

    private void markSucceeded(ShipperWalletTransaction wtx) {
        wtx.setStatus(WalletTxnStatus.SUCCEEDED);
        wtx.setProcessedAt(LocalDateTime.now());
        txRepository.save(wtx);
        System.out.println("[ShipperPayout] Rút thành công " + wtx.getTxnId()
                + " amount=" + wtx.getAmount().abs() + " -> " + wtx.getBankAccountNumber());
        if (wtx.getShipper() != null) {
            try {
                notificationService.notify(wtx.getShipper().getUserId(),
                        "WALLET_WITHDRAWAL",
                        "Đã rút tiền về tài khoản",
                        "Đã chuyển " + String.format("%,d", wtx.getAmount().abs().longValue())
                                + "đ từ ví về tài khoản ngân hàng của bạn.",
                        "WALLET", wtx.getTxnId().toString());
            } catch (Exception e) {
                System.err.println("Lỗi notify rút ví shipper " + wtx.getTxnId() + ": " + e.getMessage());
            }
        }
    }

    private void alertAdmin(ShipperWalletTransaction wtx, String msg) {
        System.err.println("[ShipperPayout][ALERT] txn=" + wtx.getTxnId()
                + " amount=" + wtx.getAmount().abs() + " :: " + msg);
    }
}
