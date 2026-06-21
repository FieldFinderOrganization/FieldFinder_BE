package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderWallet;
import com.example.FieldFinder.entity.WalletTransaction;
import com.example.FieldFinder.repository.ProviderWalletRepository;
import com.example.FieldFinder.repository.WalletTransactionRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.WalletService;
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
 * Tự động RÚT số dư rút được của ví chủ sân về TK ngân hàng qua PayOS — job nền, không chặn luồng chính.
 * Quy trình mỗi vòng: (1) tạo lệnh rút cho ví có withdrawable>0, (2) đẩy lệnh PENDING, (3) poll PROCESSING.
 * Thất bại vĩnh viễn ⇒ hoàn lại số dư ví (reverseFailedWithdrawal). Idempotent + giới hạn số lần thử.
 */
@Component
@RequiredArgsConstructor
public class WalletPayoutProcessor {

    private final ProviderWalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final WalletService walletService;
    private final BankAccountService bankAccountService;
    private final PayoutProvider payoutProvider;
    private final NotificationService notificationService;

    @Value("${provider.wallet.withdraw-max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${provider.wallet.auto-payout-interval-ms:600000}")
    @Transactional
    public void autoCreateWithdrawals() {
        for (ProviderWallet wallet : walletRepository.findAllPositive()) {
            Provider provider = wallet.getProvider();
            if (provider == null || provider.getUser() == null) continue;

            // Đã có lệnh rút dở ⇒ chờ xong rồi tính tiếp.
            if (txRepository.existsByProvider_ProviderIdAndStatusIn(
                    provider.getProviderId(), List.of(WalletTxnStatus.PENDING, WalletTxnStatus.PROCESSING))) {
                continue;
            }
            BigDecimal withdrawable = walletService.computeWithdrawable(provider.getProviderId());
            if (withdrawable.signum() <= 0) continue;

            Optional<BankAccount> bank = bankAccountService.getDefault(provider.getUser().getUserId());
            if (bank.isEmpty()) continue; // chưa liên kết TK ⇒ để dành trong ví

            walletService.createWithdrawal(provider, withdrawable, bank.get());
        }
    }

    @Scheduled(fixedDelayString = "${provider.wallet.dispatch-interval-ms:30000}")
    @Transactional
    public void dispatchPending() {
        List<WalletTransaction> pending = txRepository.findByStatusOrderByCreatedAtAsc(WalletTxnStatus.PENDING);
        if (pending.isEmpty()) return;
        Optional<BigDecimal> balance = payoutProvider.getBalance();

        for (WalletTransaction wtx : pending) {
            BigDecimal amount = wtx.getAmount().abs();
            if (balance.isPresent() && balance.get().compareTo(amount) < 0) {
                System.err.println("[WalletPayout] Số dư TK chi không đủ cho rút " + wtx.getTxnId()
                        + " (cần " + amount + ") — chờ nạp.");
                continue;
            }
            wtx.setAttemptCount(wtx.getAttemptCount() + 1);
            wtx.setLastAttemptAt(LocalDateTime.now());

            PayoutResult res = payoutProvider.disburse(new PayoutCommand(
                    wtx.getPayosReferenceId(),
                    amount.longValueExact(),
                    "Rut tien vi chu san " + wtx.getTxnId(),
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

    @Scheduled(fixedDelayString = "${provider.wallet.poll-interval-ms:45000}")
    @Transactional
    public void pollProcessing() {
        for (WalletTransaction wtx : txRepository.findByStatusOrderByCreatedAtAsc(WalletTxnStatus.PROCESSING)) {
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

    private void failOrRetry(WalletTransaction wtx, String reason) {
        if (wtx.getAttemptCount() >= maxAttempts) {
            walletService.reverseFailedWithdrawal(wtx); // quá số lần ⇒ hoàn số dư, đóng lệnh
            alertAdmin(wtx, "Rút FAILED sau " + wtx.getAttemptCount() + " lần: " + reason);
        } else {
            txRepository.save(wtx); // còn lượt ⇒ giữ PENDING, thử lại
        }
    }

    private void markSucceeded(WalletTransaction wtx) {
        wtx.setStatus(WalletTxnStatus.SUCCEEDED);
        wtx.setProcessedAt(LocalDateTime.now());
        txRepository.save(wtx);
        System.out.println("[WalletPayout] Rút thành công " + wtx.getTxnId()
                + " amount=" + wtx.getAmount().abs() + " -> " + wtx.getBankAccountNumber());
        if (wtx.getProvider() != null && wtx.getProvider().getUser() != null) {
            try {
                notificationService.notify(wtx.getProvider().getUser().getUserId(),
                        "WALLET_WITHDRAWAL",
                        "Đã rút tiền về tài khoản",
                        "Đã chuyển " + String.format("%,d", wtx.getAmount().abs().longValue())
                                + "đ từ ví về tài khoản ngân hàng của bạn.",
                        "WALLET", wtx.getTxnId().toString());
            } catch (Exception e) {
                System.err.println("Lỗi notify rút ví " + wtx.getTxnId() + ": " + e.getMessage());
            }
        }
    }

    private void alertAdmin(WalletTransaction wtx, String msg) {
        System.err.println("[WalletPayout][ALERT] txn=" + wtx.getTxnId()
                + " amount=" + wtx.getAmount().abs() + " :: " + msg);
    }
}
