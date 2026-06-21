package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProviderWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderWalletRepository extends JpaRepository<ProviderWallet, UUID> {

    Optional<ProviderWallet> findByProvider_ProviderId(UUID providerId);

    /** Khóa bi quan khi cập nhật số dư để tránh race khi nhiều giao dịch cùng lúc. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM ProviderWallet w WHERE w.provider.providerId = :providerId")
    Optional<ProviderWallet> findByProviderIdForUpdate(@Param("providerId") UUID providerId);

    /** Ví dương (rút được) để job auto-payout quét. */
    @Query("SELECT w FROM ProviderWallet w WHERE w.balance > 0")
    List<ProviderWallet> findAllPositive();

    /** Ví âm (nợ chủ sân) cho admin xem/xử lý. */
    @Query("SELECT w FROM ProviderWallet w LEFT JOIN FETCH w.provider p LEFT JOIN FETCH p.user " +
            "WHERE w.balance < 0 ORDER BY w.negativeSince ASC")
    List<ProviderWallet> findAllNegative();

    /** Ví âm quá hạn (đánh dấu âm trước cutoff) ⇒ chặn booking. */
    boolean existsByProvider_ProviderIdAndBalanceLessThanAndNegativeSinceBefore(
            UUID providerId, java.math.BigDecimal zero, LocalDateTime cutoff);
}
