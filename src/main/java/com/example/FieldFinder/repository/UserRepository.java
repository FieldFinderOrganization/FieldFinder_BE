package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByUserId(UUID userId);

    @Query("SELECT u.name FROM User u WHERE u.userId = :id")
    String findNameByProviderId(@Param("id") UUID providerId);

    Page<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String name, String email, Pageable pageable);

    @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
    List<Object[]> countByRole();

    @Query("SELECT u.status, COUNT(u) FROM User u GROUP BY u.status")
    List<Object[]> countByStatus();

    @Query("SELECT u FROM User u WHERE " +
            "(:search = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "AND (:status IS NULL OR u.status = :status) " +
            "AND (:role IS NULL OR u.role = :role)")
    Page<User> findWithFilters(@Param("search") String search,
                               @Param("status") User.Status status,
                               @Param("role") User.Role role,
                               Pageable pageable);
}