package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BankReviewStatus;
import com.example.FieldFinder.dto.req.BankAccountRequestDTO;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BankAccountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.banklookup.BankLookupService;
import com.example.FieldFinder.service.banklookup.BankLookupService.BankLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Name-match: tên TK khớp hồ sơ ⇒ APPROVED; lệch ⇒ PENDING_REVIEW + báo admin (không từ chối). */
@ExtendWith(MockitoExtension.class)
class BankAccountServiceImplTest {

    @Mock BankAccountRepository bankAccountRepository;
    @Mock UserRepository userRepository;
    @Mock BankLookupService bankLookupService;
    @Mock NotificationService notificationService;
    @Mock EmailService emailService;

    @InjectMocks BankAccountServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setUserId(userId);
        user.setName("Nguyễn Văn A");
        user.setEmail("a@example.com");
    }

    private void commonStubs() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(bankAccountRepository.findByUser_UserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(bankAccountRepository.findByUser_UserIdAndBankBinAndAccountNumber(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(bankAccountRepository.save(any(BankAccount.class))).thenAnswer(i -> {
            BankAccount b = i.getArgument(0);
            if (b.getBankAccountId() == null) b.setBankAccountId(UUID.randomUUID());
            return b;
        });
    }

    private BankAccountRequestDTO dto() {
        return new BankAccountRequestDTO("970415", "VietinBank", "1234567890", "NGUYEN VAN A");
    }

    @Test
    void nameMatchesProfile_autoApproved() {
        commonStubs();
        when(bankLookupService.lookup("970415", "1234567890"))
                .thenReturn(BankLookupResult.ok("NGUYEN VAN A")); // khớp tên hồ sơ

        BankAccount saved = service.saveOrUpdate(userId, dto());

        assertThat(saved.getReviewStatus()).isEqualTo(BankReviewStatus.APPROVED);
        verify(userRepository, never()).findByRole(any()); // không cần báo admin
    }

    @Test
    void nameMismatch_pendingReview_notifiesAdmin() {
        commonStubs();
        when(bankLookupService.lookup("970415", "1234567890"))
                .thenReturn(BankLookupResult.ok("TRAN THI B")); // lệch tên hồ sơ
        when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of());

        BankAccount saved = service.saveOrUpdate(userId, dto());

        assertThat(saved.getReviewStatus()).isEqualTo(BankReviewStatus.PENDING_REVIEW);
        assertThat(saved.getReviewNote()).contains("khác");     // 0 token chung ⇒ nghi khác người
        verify(userRepository).findByRole(User.Role.ADMIN);     // báo admin
        verify(notificationService, atLeastOnce())              // báo user đổi TK
                .notify(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void subsetName_autoApproved() {
        // Hồ sơ "Minh Triet" vs TK "HUYNH MINH TRIET" — token {MINH,TRIET} ⊆ {HUYNH,MINH,TRIET} ⇒ khớp.
        user.setName("Minh Triet");
        commonStubs();
        when(bankLookupService.lookup("970415", "1234567890"))
                .thenReturn(BankLookupResult.ok("HUYNH MINH TRIET"));

        BankAccount saved = service.saveOrUpdate(userId, dto());

        assertThat(saved.getReviewStatus()).isEqualTo(BankReviewStatus.APPROVED);
    }

    @Test
    void singleTokenMatch_pendingReview() {
        // Hồ sơ "Triet" (1 token) vs "HUYNH MINH TRIET" — 1 token quá yếu ⇒ KHÔNG auto, chờ duyệt.
        user.setName("Triet");
        commonStubs();
        when(bankLookupService.lookup("970415", "1234567890"))
                .thenReturn(BankLookupResult.ok("HUYNH MINH TRIET"));
        when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of());

        BankAccount saved = service.saveOrUpdate(userId, dto());

        assertThat(saved.getReviewStatus()).isEqualTo(BankReviewStatus.PENDING_REVIEW);
    }

    @Test
    void lookupTransientError_pendingReview() {
        commonStubs();
        when(bankLookupService.lookup("970415", "1234567890"))
                .thenReturn(BankLookupResult.unavailable("timeout")); // chưa tra được

        BankAccount saved = service.saveOrUpdate(userId, dto());

        assertThat(saved.getReviewStatus()).isEqualTo(BankReviewStatus.PENDING_REVIEW);
        assertThat(saved.isVerified()).isFalse();
    }
}
