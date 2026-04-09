package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.PasskeyCredential;
import com.example.FieldFinder.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, UUID> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    List<PasskeyCredential> findByUser(User user);

    boolean existsByCredentialId(String credentialId);
}
