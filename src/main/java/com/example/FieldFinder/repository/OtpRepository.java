package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.LoginOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OtpRepository extends JpaRepository<LoginOtp,String> {
    Optional<LoginOtp> findTopByEmailOrderByExpiryDesc(String email);
    void deleteByEmail(String email);
}
