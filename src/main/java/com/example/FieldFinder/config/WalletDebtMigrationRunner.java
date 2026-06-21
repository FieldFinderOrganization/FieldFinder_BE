package com.example.FieldFinder.config;

import com.example.FieldFinder.Enum.ProviderDebtStatus;
import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.ProviderDebt;
import com.example.FieldFinder.repository.ProviderDebtRepository;
import com.example.FieldFinder.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Chuyển các khoản ProviderDebt OUTSTANDING cũ vào ví chủ sân (một lần, idempotent).
 * ProviderDebt.amount: dương = chủ sân NỢ (ví giảm), âm = credit cho chủ sân (ví tăng).
 * Đã chuyển ⇒ đánh dấu ProviderDebt SETTLED để không xử lý lại; credit/debit cũng idempotent theo source.
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class WalletDebtMigrationRunner implements ApplicationRunner {

    private final ProviderDebtRepository providerDebtRepository;
    private final WalletService walletService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ProviderDebt> debts = providerDebtRepository.findByStatusWithProvider(ProviderDebtStatus.OUTSTANDING);
        int migrated = 0;
        for (ProviderDebt d : debts) {
            if (d.getProvider() == null || d.getAmount() == null) continue;
            String id = d.getProviderDebtId().toString();
            BigDecimal walletDelta = d.getAmount().negate(); // nợ(+) ⇒ ví giảm; credit(−) ⇒ ví tăng
            if (walletDelta.signum() > 0) {
                walletService.credit(d.getProvider(), WalletTxnType.ADJUSTMENT, walletDelta,
                        "MIGRATION", id, "Chuyển credit ProviderDebt cũ vào ví");
            } else if (walletDelta.signum() < 0) {
                walletService.debit(d.getProvider(), WalletTxnType.ADJUSTMENT, walletDelta.abs(),
                        "MIGRATION", id, "Chuyển nợ ProviderDebt cũ vào ví");
            } else {
                continue;
            }
            d.setStatus(ProviderDebtStatus.SETTLED);
            d.setSettledAt(LocalDateTime.now());
            providerDebtRepository.save(d);
            migrated++;
        }
        if (migrated > 0) {
            System.out.println("[WalletMigration] Đã chuyển " + migrated + " khoản ProviderDebt OUTSTANDING vào ví.");
        }
    }
}
