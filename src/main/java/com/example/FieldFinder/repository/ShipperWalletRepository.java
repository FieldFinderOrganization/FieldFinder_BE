package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ShipperWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipperWalletRepository extends JpaRepository<ShipperWallet, UUID> {

    Optional<ShipperWallet> findByShipper_UserId(UUID userId);

    /** Khóa bi quan khi cập nhật số dư để tránh race khi nhiều giao dịch cùng lúc. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM ShipperWallet w WHERE w.shipper.userId = :userId")
    Optional<ShipperWallet> findByUserIdForUpdate(@Param("userId") UUID userId);

    /** Ví dương (rút được) để job auto-payout quét. */
    @Query("SELECT w FROM ShipperWallet w WHERE w.balance > 0")
    List<ShipperWallet> findAllPositive();

    /** Ví âm (công nợ COD) cho admin xem/xử lý. */
    @Query("SELECT w FROM ShipperWallet w LEFT JOIN FETCH w.shipper " +
            "WHERE w.balance < 0 ORDER BY w.negativeSince ASC")
    List<ShipperWallet> findAllNegative();

    /** Ví âm quá hạn (đánh dấu âm trước cutoff) ⇒ chặn nhận đơn. */
    boolean existsByShipper_UserIdAndBalanceLessThanAndNegativeSinceBefore(
            UUID userId, java.math.BigDecimal zero, LocalDateTime cutoff);
}
