package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.ProviderDebtStatus;
import com.example.FieldFinder.entity.ProviderDebt;
import com.example.FieldFinder.repository.ProviderDebtRepository;
import com.example.FieldFinder.service.ProviderDebtService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProviderDebtServiceImpl implements ProviderDebtService {

    private final ProviderDebtRepository providerDebtRepository;

    @Override
    public List<ProviderDebt> listOutstanding() {
        return providerDebtRepository.findByStatusWithProvider(ProviderDebtStatus.OUTSTANDING);
    }

    @Override
    @Transactional
    public ProviderDebt settle(UUID providerDebtId) {
        ProviderDebt d = providerDebtRepository.findById(providerDebtId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khoản nợ!"));
        d.setStatus(ProviderDebtStatus.SETTLED);
        d.setSettledAt(LocalDateTime.now());
        return providerDebtRepository.save(d);
    }

    @Override
    @Transactional
    public ProviderDebt waive(UUID providerDebtId) {
        ProviderDebt d = providerDebtRepository.findById(providerDebtId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khoản nợ!"));
        d.setStatus(ProviderDebtStatus.WAIVED);
        d.setSettledAt(LocalDateTime.now());
        return providerDebtRepository.save(d);
    }

    @Override
    public boolean isBookingBlocked(UUID providerId) {
        return providerDebtRepository.existsByProvider_ProviderIdAndStatusAndDeadlineAtBefore(
                providerId, ProviderDebtStatus.OUTSTANDING, LocalDateTime.now());
    }
}
