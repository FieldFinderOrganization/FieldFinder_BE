package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.RefundStatus;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.repository.BankAccountRepository;
import com.example.FieldFinder.repository.RefundRequestRepository;
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
 * Xử lý hoàn tiền mặt qua PayOS payout bằng các job nền — KHÔNG chặn luồng hủy đơn.
 * Tách 3 việc: (1) đẩy lệnh chi PENDING, (2) poll trạng thái PROCESSING, (3) canh deadline.
 * Mọi bước idempotent + giới hạn số lần thử để chịu được nhiều đơn hoàn cùng lúc.
 */
@Component
@RequiredArgsConstructor
public class RefundPayoutProcessor {

    private final RefundRequestRepository refundRequestRepository;
    private final BankAccountRepository bankAccountRepository;
    private final PayoutProvider payoutProvider;

    @Value("${refund.payout.max-attempts:5}")
    private int maxAttempts;

    /** (1) Đẩy lệnh chi cho các khoản PAYOUT_PENDING. */
    @Scheduled(fixedDelayString = "${refund.payout.dispatch-interval-ms:30000}")
    @Transactional
    public void dispatchPending() {
        List<RefundRequest> pending =
                refundRequestRepository.findByStatusOrderByCreatedAtAsc(RefundStatus.PAYOUT_PENDING);
        if (pending.isEmpty()) return;

        Optional<BigDecimal> balance = payoutProvider.getBalance();

        for (RefundRequest r : pending) {
            // Đủ số dư TK chi mới đẩy; thiếu thì để PENDING, chờ nạp tiền
            if (balance.isPresent() && balance.get().compareTo(r.getAmount()) < 0) {
                System.err.println("[Payout] Số dư TK chi không đủ cho refund " + r.getRefundId()
                        + " (cần " + r.getAmount() + ", có " + balance.get() + ") — chờ nạp.");
                continue;
            }

            r.setAttemptCount(r.getAttemptCount() + 1);
            r.setLastAttemptAt(LocalDateTime.now());

            PayoutResult res = payoutProvider.disburse(new PayoutCommand(
                    r.getPayosReferenceId(),
                    r.getAmount().longValueExact(),
                    buildDescription(r),
                    r.getBankBin(),
                    r.getBankAccountNumber()));

            r.setPayosTxnState(res.providerState());
            r.setFailureReason(res.message());

            switch (res.state()) {
                case SUCCEEDED -> {
                    r.setPayosPayoutId(res.payoutId());
                    markSucceeded(r);
                }
                case PROCESSING -> {
                    r.setPayosPayoutId(res.payoutId());
                    r.setStatus(RefundStatus.PAYOUT_PROCESSING);
                }
                case FAILED, UNKNOWN -> failOrRetry(r, res.message());
            }
            refundRequestRepository.save(r);
        }
    }

    /** (2) Poll trạng thái các lệnh đã gửi (PAYOUT_PROCESSING). */
    @Scheduled(fixedDelayString = "${refund.payout.poll-interval-ms:45000}")
    @Transactional
    public void pollProcessing() {
        List<RefundRequest> processing =
                refundRequestRepository.findByStatusOrderByCreatedAtAsc(RefundStatus.PAYOUT_PROCESSING);
        for (RefundRequest r : processing) {
            if (r.getPayosPayoutId() == null) continue;
            PayoutResult res = payoutProvider.getStatus(r.getPayosPayoutId());
            r.setPayosTxnState(res.providerState());
            switch (res.state()) {
                case SUCCEEDED -> markSucceeded(r);
                case FAILED -> {
                    r.setFailureReason(res.message());
                    r.setStatus(RefundStatus.PAYOUT_FAILED);
                    alertAdmin(r, "Payout FAILED khi poll: " + res.message());
                }
                case PROCESSING, UNKNOWN -> { /* chờ vòng sau */ }
            }
            refundRequestRepository.save(r);
        }
    }

    /** (3) Canh deadline: khoản chưa xong mà quá hạn ⇒ cảnh báo admin. */
    @Scheduled(fixedDelayString = "${refund.payout.deadline-interval-ms:600000}")
    @Transactional(readOnly = true)
    public void checkDeadlines() {
        List<RefundRequest> overdue = refundRequestRepository.findByStatusInAndDeadlineAtBefore(
                List.of(RefundStatus.PAYOUT_PENDING, RefundStatus.PAYOUT_PROCESSING),
                LocalDateTime.now());
        for (RefundRequest r : overdue) {
            alertAdmin(r, "QUÁ HẠN hoàn tiền (deadline " + r.getDeadlineAt() + ", status " + r.getStatus() + ")");
        }
    }

    private void failOrRetry(RefundRequest r, String reason) {
        if (r.getAttemptCount() >= maxAttempts) {
            r.setStatus(RefundStatus.PAYOUT_FAILED);
            alertAdmin(r, "Payout FAILED sau " + r.getAttemptCount() + " lần thử: " + reason);
        }
        // còn lượt ⇒ giữ PAYOUT_PENDING, vòng dispatch sau thử lại
    }

    private void markSucceeded(RefundRequest r) {
        r.setStatus(RefundStatus.PAYOUT_SUCCEEDED);
        r.setProcessedAt(LocalDateTime.now());
        // Lần chi thành công ⇒ coi như TK đã xác thực
        bankAccountRepository.findByUser_UserIdAndBankBinAndAccountNumber(
                        r.getUser().getUserId(), r.getBankBin(), r.getBankAccountNumber())
                .ifPresent(b -> {
                    if (!b.isVerified()) {
                        b.setVerified(true);
                        bankAccountRepository.save(b);
                    }
                });
        System.out.println("[Payout] Hoàn tiền thành công refund " + r.getRefundId()
                + " amount=" + r.getAmount() + " -> " + r.getBankAccountNumber());
        // TODO: gửi thông báo in-app + email cho user "đã hoàn tiền"
    }

    private void alertAdmin(RefundRequest r, String msg) {
        System.err.println("[Payout][ALERT] refund=" + r.getRefundId()
                + " user=" + (r.getUser() != null ? r.getUser().getUserId() : null)
                + " amount=" + r.getAmount() + " :: " + msg);
        // TODO: đẩy notification cho admin
    }

    private static String buildDescription(RefundRequest r) {
        return "Hoan tien " + r.getSourceType() + " " + r.getSourceId();
    }
}
