package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    Optional<RefundRequest> findBySourceTypeAndSourceId(RefundSourceType sourceType, String sourceId);

    boolean existsBySourceTypeAndSourceId(RefundSourceType sourceType, String sourceId);

    List<RefundRequest> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);
}