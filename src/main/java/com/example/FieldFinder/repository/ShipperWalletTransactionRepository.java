package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.entity.ShipperWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShipperWalletTransactionRepository extends JpaRepository<ShipperWalletTransaction, UUID> {

    List<ShipperWalletTransaction> findByShipper_UserIdOrderByCreatedAtDesc(UUID userId);

    /** Idempotency: một nguồn (đơn hàng) chỉ sinh 1 giao dịch cùng loại. */
    boolean existsByTypeAndSourceTypeAndSourceId(ShipperWalletTxnType type, String sourceType, String sourceId);

    /** Cho ShipperWalletPayoutProcessor quét lệnh rút theo trạng thái. */
    List<ShipperWalletTransaction> findByStatusOrderByCreatedAtAsc(WalletTxnStatus status);

    /** Shipper đang có lệnh rút dở (PENDING/PROCESSING) ⇒ không tạo lệnh mới. */
    boolean existsByShipper_UserIdAndStatusIn(UUID userId, java.util.Collection<WalletTxnStatus> statuses);
}
