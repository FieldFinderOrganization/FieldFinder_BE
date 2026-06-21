package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    Optional<BankAccount> findByUser_UserIdAndIsDefaultTrue(UUID userId);

    Optional<BankAccount> findByUser_UserIdAndBankBinAndAccountNumber(
            UUID userId, String bankBin, String accountNumber);

    /** TK đang chờ admin duyệt (tên lệch hồ sơ). */
    List<BankAccount> findByReviewStatusOrderByUpdatedAtDesc(
            com.example.FieldFinder.Enum.BankReviewStatus reviewStatus);
}
