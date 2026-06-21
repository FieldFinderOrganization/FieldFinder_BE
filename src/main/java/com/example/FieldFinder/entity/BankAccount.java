package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tài khoản ngân hàng người dùng đăng ký để NHẬN tiền hoàn (PayOS payout).
 * Mọi khoản hoàn — dù lúc thanh toán dùng phương thức gì — đều chuyển về TK này.
 * Một user có thể đổi TK; chỉ một TK {@code isDefault=true} được dùng để hoàn.
 */
@Entity
@Table(
        name = "BankAccounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bank_account_user_acc",
                columnNames = {"UserId", "BankBin", "AccountNumber"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "BankAccountId", updatable = false, nullable = false)
    private UUID bankAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private User user;

    /** Mã BIN ngân hàng (VietQR/NAPAS), ví dụ 970422 = MB Bank. PayOS payout dùng làm toBin. */
    @Column(name = "BankBin", nullable = false, length = 12)
    private String bankBin;

    /** Tên ngân hàng hiển thị, ví dụ "MB Bank". */
    @Column(name = "BankName", length = 120)
    private String bankName;

    @Column(name = "AccountNumber", nullable = false, length = 32)
    private String accountNumber;

    /** Tên chủ TK (in hoa, không dấu) — đối chiếu với tên PayOS trả về khi payout. */
    @Column(name = "AccountName", nullable = false, length = 120)
    private String accountName;

    /** TK mặc định để nhận hoàn tiền. Chỉ một TK/user là true. */
    @Builder.Default
    @Column(name = "IsDefault", nullable = false)
    private boolean isDefault = true;

    /**
     * Đã xác thực tên chủ TK khớp với ngân hàng chưa.
     * Đặt true khi PayOS trả toAccountName khớp (sau lần payout đầu, hoặc validateDestination).
     */
    @Builder.Default
    @Column(name = "Verified", nullable = false)
    private boolean verified = false;

    /**
     * Duyệt theo khớp tên với hồ sơ chủ tài khoản app. Khớp ⇒ APPROVED (auto, được nhận tiền);
     * lệch / chưa tra được ⇒ PENDING_REVIEW (admin xét). Chỉ TK APPROVED mới được payout.
     */
    @Builder.Default
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "ReviewStatus", nullable = false, length = 16)
    private com.example.FieldFinder.Enum.BankReviewStatus reviewStatus =
            com.example.FieldFinder.Enum.BankReviewStatus.APPROVED;

    /** Lý do/note khi PENDING/REJECTED (vd "tên lệch hồ sơ"). */
    @Column(name = "ReviewNote", length = 300)
    private String reviewNote;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;
}
