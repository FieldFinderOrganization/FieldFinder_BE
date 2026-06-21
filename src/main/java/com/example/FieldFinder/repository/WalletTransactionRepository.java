package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    List<WalletTransaction> findByProvider_ProviderIdOrderByCreatedAtDesc(UUID providerId);

    /** Idempotency: một nguồn (booking) chỉ sinh 1 giao dịch cùng loại. */
    boolean existsByTypeAndSourceTypeAndSourceId(WalletTxnType type, String sourceType, String sourceId);

    /** Cho WalletPayoutProcessor quét lệnh rút theo trạng thái. */
    List<WalletTransaction> findByStatusOrderByCreatedAtAsc(WalletTxnStatus status);

    /** Provider đang có lệnh rút dở (PENDING/PROCESSING) ⇒ không tạo lệnh mới. */
    boolean existsByProvider_ProviderIdAndStatusIn(UUID providerId, java.util.Collection<WalletTxnStatus> statuses);
}
