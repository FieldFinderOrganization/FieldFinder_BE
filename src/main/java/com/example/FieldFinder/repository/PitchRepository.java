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

    @Override
    @EntityGraph(value = "Pitch.withProviderDetails", type = EntityGraph.EntityGraphType.LOAD)
    Page<Pitch> findAll(Specification<Pitch> spec, Pageable pageable);

    List<Pitch> findByProviderAddressProviderAddressId(UUID providerAddressId);

    /** Sân chưa có toạ độ riêng (cần seed jitter quanh tâm khu vực). */
    List<Pitch> findByLatitudeIsNullOrLongitudeIsNull();

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

    @Query(value = """
            SELECT p.* FROM pitches p
            JOIN provider_address pa ON pa.provider_address_id = p.provider_address_id
            WHERE pa.latitude IS NOT NULL AND pa.longitude IS NOT NULL
              AND p.pitch_id <> :excludeId
            ORDER BY (6371 * acos(
                cos(radians(:lat)) * cos(radians(pa.latitude)) *
                cos(radians(pa.longitude) - radians(:lng)) +
                sin(radians(:lat)) * sin(radians(pa.latitude))
            )) ASC
            LIMIT :maxResults
            """, nativeQuery = true)
    List<Pitch> findNearbyPitches(@Param("excludeId") UUID excludeId,
                                  @Param("lat") double lat,
                                  @Param("lng") double lng,
                                  @Param("maxResults") int maxResults);

    @Query(value = """
            SELECT p.* FROM pitches p
            JOIN provider_address pa ON pa.provider_address_id = p.provider_address_id
            WHERE LOWER(pa.address) LIKE LOWER(CONCAT('%', :district, '%'))
              AND p.pitch_id <> :excludeId
            LIMIT :maxResults
            """, nativeQuery = true)
    List<Pitch> findByDistrictKeyword(@Param("excludeId") UUID excludeId,
                                      @Param("district") String district,
                                      @Param("maxResults") int maxResults);

    @Query("""
            SELECT p FROM Pitch p
            LEFT JOIN Review r ON r.pitch = p
            WHERE p.pitchId <> :excludeId
            GROUP BY p
            HAVING AVG(r.rating) IS NOT NULL
            ORDER BY AVG(r.rating) DESC, COUNT(r) DESC
            """)
    List<Pitch> findTopRatedPitches(@Param("excludeId") UUID excludeId, Pageable pageable);

    @Query("""
            SELECT DISTINCT bd.pitch.pitchId FROM BookingDetail bd
            WHERE bd.booking.user.userId = :userId
              AND bd.pitch.pitchId <> :excludeId
            ORDER BY bd.pitch.pitchId
            """)
    List<UUID> findBookedPitchIdsByUser(@Param("userId") UUID userId,
                                        @Param("excludeId") UUID excludeId);
}