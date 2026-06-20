package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.ProviderDebtStatus;
import com.example.FieldFinder.entity.ProviderDebt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderDebtRepository extends JpaRepository<ProviderDebt, UUID> {

    boolean existsBySourceBookingId(String sourceBookingId);

    Optional<ProviderDebt> findBySourceBookingId(String sourceBookingId);

    List<ProviderDebt> findByProvider_ProviderIdAndStatus(UUID providerId, ProviderDebtStatus status);

    /** Fetch provider + user để map tên chủ sân (tránh LazyInit khi map DTO ngoài tx). */
    @Query("SELECT d FROM ProviderDebt d " +
            "LEFT JOIN FETCH d.provider p LEFT JOIN FETCH p.user " +
            "WHERE d.status = :status ORDER BY d.deadlineAt ASC")
    List<ProviderDebt> findByStatusWithProvider(@Param("status") ProviderDebtStatus status);

    /** Chủ sân có khoản nợ quá hạn chưa trả ⇒ dùng để chặn nhận booking. */
    boolean existsByProvider_ProviderIdAndStatusAndDeadlineAtBefore(
            UUID providerId, ProviderDebtStatus status, LocalDateTime cutoff);
}
