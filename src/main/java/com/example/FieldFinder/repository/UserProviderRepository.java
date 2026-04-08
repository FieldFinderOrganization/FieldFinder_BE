package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.UserProvider;
import com.example.FieldFinder.entity.UserProvider.ProviderName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProviderRepository extends JpaRepository<UserProvider, UUID> {

    @Query("SELECT up FROM UserProvider up WHERE up.providerName = :providerName AND up.providerUid = :providerUid")
    Optional<UserProvider> findByProviderNameAndProviderUid(
            @Param("providerName") ProviderName providerName,
            @Param("providerUid") String providerUid);
}
