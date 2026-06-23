package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.WalletTopup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WalletTopupRepository extends JpaRepository<WalletTopup, UUID> {

    Optional<WalletTopup> findByTransactionId(String transactionId);

    Optional<WalletTopup> findByOrderCode(long orderCode);

    /**
     * Chuyển PENDING → CREDITED một cách nguyên tử. Chỉ đúng 1 caller nhận được 1 (winner)
     * dù webhook bắn trùng — winner mới được cộng ví. Trả số dòng bị đổi (0 = đã xử lý).
     */
    @Modifying
    @Query("UPDATE WalletTopup t SET t.status = 'CREDITED', t.creditedAt = :now " +
            "WHERE t.topupId = :id AND t.status = 'PENDING'")
    int markCreditedIfPending(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
