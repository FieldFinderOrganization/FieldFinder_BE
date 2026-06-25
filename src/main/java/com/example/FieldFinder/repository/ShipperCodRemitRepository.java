package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ShipperCodRemit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ShipperCodRemitRepository extends JpaRepository<ShipperCodRemit, UUID> {

    Optional<ShipperCodRemit> findByTransactionId(String transactionId);

    Optional<ShipperCodRemit> findByOrderCode(long orderCode);

    /**
     * Chuyển PENDING → CREDITED nguyên tử. Chỉ đúng 1 caller nhận được 1 (winner) dù webhook
     * bắn trùng — winner mới được giảm nợ ví. Trả số dòng bị đổi (0 = đã xử lý).
     */
    @Modifying
    @Query("UPDATE ShipperCodRemit r SET r.status = 'CREDITED', r.creditedAt = :now " +
            "WHERE r.remitId = :id AND r.status = 'PENDING'")
    int markCreditedIfPending(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
