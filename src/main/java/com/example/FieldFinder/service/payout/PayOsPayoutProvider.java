package com.example.FieldFinder.service.payout;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;
import vn.payos.exception.APIException;
import vn.payos.model.v1.payouts.Payout;
import vn.payos.model.v1.payouts.PayoutRequests;
import vn.payos.model.v1.payouts.PayoutTransaction;
import vn.payos.model.v1.payouts.PayoutTransactionState;
import vn.payos.model.v1.payoutsAccount.PayoutAccountInfo;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;

/**
 * Chi tiền thật qua PayOS Chi hộ (kênh chi riêng — credentials KHÁC kênh thu).
 * Bật khi {@code payos.payout.provider=payos} (mặc định). SDK tự lo x-signature + idempotency.
 */
@Component
@ConditionalOnProperty(name = "payos.payout.provider", havingValue = "payos", matchIfMissing = true)
public class PayOsPayoutProvider implements PayoutProvider {

    @Value("${payos.payout.clientId}")
    private String clientId;

    @Value("${payos.payout.apiKey}")
    private String apiKey;

    @Value("${payos.payout.checksumKey}")
    private String checksumKey;

    private PayOS client;

    @PostConstruct
    void init() {
        this.client = new PayOS(clientId, apiKey, checksumKey);
    }

    @Override
    public PayoutResult disburse(PayoutCommand cmd) {
        PayoutRequests req = PayoutRequests.builder()
                .referenceId(cmd.referenceId())
                .amount(cmd.amountVnd())
                .description(sanitizeDescription(cmd.description()))
                .toBin(cmd.toBin())
                .toAccountNumber(cmd.toAccountNumber())
                .build();
        try {
            // idempotencyKey = referenceId ⇒ gọi lại không double-chi
            Payout payout = client.payouts().create(req, cmd.referenceId());
            return toResult(payout);
        } catch (APIException e) {
            // Lỗi nghiệp vụ PayOS (số dư, TK nhận...) — không có payoutId, job sẽ retry/đánh dấu fail
            return new PayoutResult(null, cmd.referenceId(), PayoutState.FAILED,
                    null, null, errDesc(e));
        } catch (Exception e) {
            // Lỗi hạ tầng (timeout, mạng) — UNKNOWN ⇒ job thử lại
            return new PayoutResult(null, cmd.referenceId(), PayoutState.UNKNOWN,
                    null, null, e.getMessage());
        }
    }

    @Override
    public PayoutResult getStatus(String payoutId) {
        try {
            Payout payout = client.payouts().get(payoutId);
            return toResult(payout);
        } catch (Exception e) {
            return new PayoutResult(payoutId, null, PayoutState.UNKNOWN, null, null, e.getMessage());
        }
    }

    @Override
    public Optional<BigDecimal> getBalance() {
        try {
            PayoutAccountInfo info = client.payoutsAccount().balance();
            if (info == null || info.getBalance() == null) return Optional.empty();
            return Optional.of(new BigDecimal(info.getBalance().trim()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private PayoutResult toResult(Payout payout) {
        PayoutTransaction txn = firstTxn(payout);
        PayoutTransactionState st = txn != null ? txn.getState() : null;
        PayoutState mapped = mapState(st);
        String providerState = st != null ? st.name() : String.valueOf(payout.getApprovalState());
        String msg = txn != null ? txn.getErrorMessage() : null;
        String toName = txn != null ? txn.getToAccountName() : null;
        return new PayoutResult(payout.getId(), payout.getReferenceId(),
                mapped, providerState, toName, msg);
    }

    private static PayoutTransaction firstTxn(Payout payout) {
        List<PayoutTransaction> txns = payout != null ? payout.getTransactions() : null;
        return (txns != null && !txns.isEmpty()) ? txns.get(0) : null;
    }

    private static PayoutState mapState(PayoutTransactionState st) {
        if (st == null) return PayoutState.PROCESSING;
        return switch (st) {
            case SUCCEEDED -> PayoutState.SUCCEEDED;
            case FAILED, CANCELLED, REVERSED -> PayoutState.FAILED;
            case RECEIVED, PROCESSING, ON_HOLD -> PayoutState.PROCESSING;
        };
    }

    private static String errDesc(APIException e) {
        try {
            return e.getErrorDesc().orElse(e.getMessage());
        } catch (Exception ignore) {
            return e.getMessage();
        }
    }

    /** PayOS memo: ASCII, bỏ dấu, gọn ≤ 25 ký tự. */
    private static String sanitizeDescription(String s) {
        if (s == null) return "Hoan tien";
        String ascii = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (ascii.isEmpty()) ascii = "Hoan tien";
        return ascii.length() > 25 ? ascii.substring(0, 25).trim() : ascii;
    }
}
