package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.PointTxType;
import com.example.FieldFinder.entity.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, UUID> {

    List<PointTransaction> findTop50ByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    /** Idempotency: 1 đơn chỉ có tối đa 1 dòng EARN_ORDER. */
    Optional<PointTransaction> findFirstByTypeAndRefOrderId(PointTxType type, Long refOrderId);
}
