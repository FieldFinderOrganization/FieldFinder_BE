package com.example.FieldFinder.service.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giả lập payout cho demo/khóa luận — KHÔNG tiền thật, KHÔNG cần nạp TK chi.
 * Bật khi {@code payos.payout.provider=mock}. Mô phỏng độ trễ: PROCESSING rồi SUCCEEDED sau ~8s.
 */
@Component
@ConditionalOnProperty(name = "payos.payout.provider", havingValue = "mock")
public class MockPayoutProvider implements PayoutProvider {

    private static final long SETTLE_MILLIS = 8_000L;

    /** payoutId -> thời điểm tạo (mô phỏng ngân hàng xử lý). */
    private final ConcurrentHashMap<String, Long> ledger = new ConcurrentHashMap<>();

    @Override
    public PayoutResult disburse(PayoutCommand cmd) {
        String payoutId = "mock_" + UUID.randomUUID();
        ledger.put(payoutId, System.currentTimeMillis());
        System.out.println("[MockPayout] disburse ref=" + cmd.referenceId()
                + " amount=" + cmd.amountVnd() + " -> " + cmd.toBin() + "/" + cmd.toAccountNumber());
        return new PayoutResult(payoutId, cmd.referenceId(), PayoutState.PROCESSING,
                "PROCESSING", "MOCK ACCOUNT NAME", null);
    }

    @Override
    public PayoutResult getStatus(String payoutId) {
        Long created = ledger.get(payoutId);
        boolean settled = created != null && (System.currentTimeMillis() - created) >= SETTLE_MILLIS;
        PayoutState state = settled ? PayoutState.SUCCEEDED : PayoutState.PROCESSING;
        return new PayoutResult(payoutId, null, state, state.name(), "MOCK ACCOUNT NAME", null);
    }

    @Override
    public Optional<BigDecimal> getBalance() {
        return Optional.of(new BigDecimal("1000000000")); // 1 tỷ ảo
    }
}
