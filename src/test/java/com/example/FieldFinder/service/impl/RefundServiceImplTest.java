package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.DiscountKind;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.Enum.RefundStatus;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.RefundRequestRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceImplTest {

    @Mock DiscountRepository discountRepository;
    @Mock UserDiscountRepository userDiscountRepository;
    @Mock RefundRequestRepository refundRequestRepository;
    @Mock EmailService emailService;

    @InjectMocks RefundServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(UUID.randomUUID());
    }

    @Test
    void issueRefundCredit_throws_whenUserNull() {
        assertThatThrownBy(() -> service.issueRefundCredit(
                null, RefundSourceType.ORDER, "1", BigDecimal.TEN, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User required");
    }

    @Test
    void issueRefundCredit_throws_whenAmountNullOrZero() {
        assertThatThrownBy(() -> service.issueRefundCredit(
                user, RefundSourceType.ORDER, "1", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueRefundCredit(
                user, RefundSourceType.ORDER, "1", BigDecimal.ZERO, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueRefundCredit_throws_whenRefundExists() {
        when(refundRequestRepository.findBySourceTypeAndSourceId(
                RefundSourceType.ORDER, "42"))
                .thenReturn(Optional.of(new RefundRequest()));

        assertThatThrownBy(() -> service.issueRefundCredit(
                user, RefundSourceType.ORDER, "42", BigDecimal.TEN, "x"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("đã tồn tại");
    }

    @Test
    void issueRefundCredit_happyPath_savesEntitiesAndReturnsIssued() {
        when(refundRequestRepository.findBySourceTypeAndSourceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discountRepository.existsByCode(anyString())).thenReturn(false);
        when(discountRepository.save(any(Discount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(refundRequestRepository.save(any(RefundRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BigDecimal amount = new BigDecimal("250000");
        RefundRequest result = service.issueRefundCredit(
                user, RefundSourceType.ORDER, "42", amount, "test reason");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(result.getProcessedAt()).isNotNull();
        assertThat(result.getIssuedDiscount()).isNotNull();
        assertThat(result.getIssuedDiscount().getCode()).startsWith("RF-");
        assertThat(result.getIssuedDiscount().getKind())
                .isEqualTo(DiscountKind.REFUND_CREDIT);
        assertThat(result.getIssuedDiscount().getValue()).isEqualByComparingTo(amount);
        assertThat(result.getAmount()).isEqualByComparingTo(amount);
        assertThat(result.getReason()).isEqualTo("test reason");

        // Save chain: RefundRequest twice (REQUESTED then ISSUED), Discount once, UserDiscount once.
        verify(refundRequestRepository, times(2)).save(any(RefundRequest.class));
        verify(discountRepository, times(1)).save(any(Discount.class));

        ArgumentCaptor<UserDiscount> udCap = ArgumentCaptor.forClass(UserDiscount.class);
        verify(userDiscountRepository).save(udCap.capture());
        assertThat(udCap.getValue().getRemainingValue()).isEqualByComparingTo(amount);
        assertThat(udCap.getValue().isUsed()).isFalse();
        assertThat(udCap.getValue().getUser()).isSameAs(user);

        // Email gửi luôn (no active tx in test)
        verify(emailService).sendRefundCodeIssued(any(RefundRequest.class));
    }

    @Test
    void generateRefundDiscount_setsRefundCreditDefaults() {
        when(discountRepository.existsByCode(anyString())).thenReturn(false);

        Discount d = service.generateRefundDiscount(new BigDecimal("100"));

        assertThat(d.getKind()).isEqualTo(DiscountKind.REFUND_CREDIT);
        assertThat(d.getDiscountType()).isEqualTo(Discount.DiscountType.FIXED_AMOUNT);
        assertThat(d.getStatus()).isEqualTo(Discount.DiscountStatus.ACTIVE);
        assertThat(d.getCode()).startsWith("RF-");
        assertThat(d.getCode().length()).isEqualTo(13); // "RF-" + 10 chars
        assertThat(d.getEndDate()).isEqualTo(d.getStartDate().plusDays(90));
        assertThat(d.getQuantity()).isEqualTo(1);
    }

    @Test
    void generateRefundDiscount_throws_whenAllCodesCollide() {
        when(discountRepository.existsByCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.generateRefundDiscount(BigDecimal.ONE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Không sinh được mã");
    }

    @Test
    void findBySource_delegatesToRepo() {
        RefundRequest stub = new RefundRequest();
        when(refundRequestRepository.findBySourceTypeAndSourceId(
                RefundSourceType.BOOKING, "abc"))
                .thenReturn(Optional.of(stub));

        Optional<RefundRequest> result = service.findBySource(
                RefundSourceType.BOOKING, "abc");

        assertThat(result).containsSame(stub);
    }

    @Test
    void expireRefundCodes_marksOnlyExpiredRefundCredits() {
        LocalDate today = LocalDate.now();

        Discount expiredRefund = Discount.builder()
                .kind(DiscountKind.REFUND_CREDIT)
                .status(Discount.DiscountStatus.ACTIVE)
                .endDate(today.minusDays(1))
                .build();
        Discount activeRefund = Discount.builder()
                .kind(DiscountKind.REFUND_CREDIT)
                .status(Discount.DiscountStatus.ACTIVE)
                .endDate(today.plusDays(1))
                .build();
        Discount expiredPromo = Discount.builder()
                .kind(DiscountKind.PROMOTION)
                .status(Discount.DiscountStatus.ACTIVE)
                .endDate(today.minusDays(2))
                .build();

        when(discountRepository.findAll())
                .thenReturn(List.of(expiredRefund, activeRefund, expiredPromo));

        service.expireRefundCodes();

        assertThat(expiredRefund.getStatus()).isEqualTo(Discount.DiscountStatus.EXPIRED);
        assertThat(activeRefund.getStatus()).isEqualTo(Discount.DiscountStatus.ACTIVE);
        assertThat(expiredPromo.getStatus()).isEqualTo(Discount.DiscountStatus.ACTIVE);
        verify(discountRepository, times(1)).save(expiredRefund);
        verify(discountRepository, never()).save(activeRefund);
        verify(discountRepository, never()).save(expiredPromo);
    }
}