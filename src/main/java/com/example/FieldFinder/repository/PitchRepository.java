package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Pitch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PitchRepository extends JpaRepository<Pitch, UUID>, JpaSpecificationExecutor<Pitch> {

    // Override findAll to use EntityGraph - eliminates N+1 queries for providerAddress->provider->user
    @Override
    @EntityGraph(value = "Pitch.withProviderDetails", type = EntityGraph.EntityGraphType.LOAD)
    Page<Pitch> findAll(Specification<Pitch> spec, Pageable pageable);

    List<Pitch> findByProviderAddressProviderAddressId(UUID providerAddressId);

    Optional<Pitch> findById(UUID uuid);

    @Query("SELECT p.type, COUNT(p) FROM Pitch p GROUP BY p.type")
    List<Object[]> countByType();

    @Query(value = "SELECT p FROM Pitch p " +
            "LEFT JOIN p.providerAddress pa LEFT JOIN pa.provider pr LEFT JOIN pr.user pu " +
            "WHERE (:search = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%',:search,'%')) " +
            "   OR LOWER(pu.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
            "AND (:type IS NULL OR p.type = :type)",
            countQuery = "SELECT COUNT(p) FROM Pitch p " +
                    "LEFT JOIN p.providerAddress pa LEFT JOIN pa.provider pr LEFT JOIN pr.user pu " +
                    "WHERE (:search = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%',:search,'%')) " +
                    "   OR LOWER(pu.name) LIKE LOWER(CONCAT('%',:search,'%'))) " +
                    "AND (:type IS NULL OR p.type = :type)")
    Page<Pitch> findWithFilters(@Param("search") String search,
                                @Param("type") Pitch.PitchType type,
                                Pageable pageable);
}