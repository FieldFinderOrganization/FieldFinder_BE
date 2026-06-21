package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BankAccountRequestDTO;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BankAccountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.banklookup.BankLookupService;
import com.example.FieldFinder.service.banklookup.BankLookupService.BankLookupResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final UserRepository userRepository;
    private final BankLookupService bankLookupService;
    private final com.example.FieldFinder.service.NotificationService notificationService;
    private final com.example.FieldFinder.service.EmailService emailService;

    @Override
    public List<BankAccount> listMine(UUID userId) {
        return bankAccountRepository.findByUser_UserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public Optional<BankAccount> getDefault(UUID userId) {
        return bankAccountRepository.findByUser_UserIdAndIsDefaultTrue(userId);
    }

    @Override
    @Transactional
    public BankAccount saveOrUpdate(UUID userId, BankAccountRequestDTO dto) {
        if (dto == null) throw new IllegalArgumentException("Thiếu dữ liệu tài khoản!");
        String bin = trimToNull(dto.bankBin());
        String accNo = trimToNull(dto.accountNumber());
        String accName = normalizeName(dto.accountName());
        if (bin == null) throw new IllegalArgumentException("Thiếu mã ngân hàng (BIN)!");
        if (accNo == null || !accNo.matches("\\d{6,19}")) {
            throw new IllegalArgumentException("Số tài khoản không hợp lệ!");
        }
        if (accName == null || accName.isBlank()) {
            throw new IllegalArgumentException("Thiếu tên chủ tài khoản!");
        }

        // Xác thực TK là THẬT qua tra cứu tên chủ TK (VietQR/NAPAS).
        // - TK ảo ⇒ từ chối. - Lỗi tạm thời ⇒ vẫn lưu nhưng verified=false (gate lại lúc chi).
        BankLookupResult lk = bankLookupService.lookup(bin, accNo);
        if (!lk.ok() && !lk.transientError()) {
            throw new IllegalArgumentException("Số tài khoản không tồn tại hoặc không khớp ngân hàng!"
                    + (lk.message() != null ? " (" + lk.message() + ")" : ""));
        }
        boolean verified = lk.ok();
        if (lk.ok() && lk.accountName() != null && !lk.accountName().isBlank()) {
            // Lấy tên ngân hàng trả về làm CHUẨN, không tin tên user gõ
            accName = normalizeName(lk.accountName());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng!"));

        // Bỏ cờ default ở mọi TK hiện có của user
        List<BankAccount> existing = bankAccountRepository.findByUser_UserIdOrderByCreatedAtDesc(userId);
        for (BankAccount b : existing) {
            if (b.isDefault()) {
                b.setDefault(false);
                bankAccountRepository.save(b);
            }
        }

        // Upsert theo (user, bin, accNo)
        BankAccount acc = bankAccountRepository
                .findByUser_UserIdAndBankBinAndAccountNumber(userId, bin, accNo)
                .orElseGet(() -> BankAccount.builder()
                        .user(user)
                        .bankBin(bin)
                        .accountNumber(accNo)
                        .createdAt(LocalDateTime.now())
                        .build());

        acc.setBankBin(bin);
        acc.setBankName(trimToNull(dto.bankName()));
        acc.setAccountNumber(accNo);
        acc.setAccountName(accName);
        acc.setVerified(verified);
        acc.setDefault(true);
        acc.setUpdatedAt(LocalDateTime.now());

        // Khớp tên kiểu TOKEN-SET (tham chiếu Confirmation of Payee / penny-drop của fintech): khớp mạnh
        // ⇒ AUTO-DUYỆT; khớp một phần / khác người / chưa tra được ⇒ CHỜ ADMIN (không từ chối thẳng).
        String profileName = normalizeName(user.getName());
        com.example.FieldFinder.Enum.BankReviewStatus status;
        String note = null;
        if (verified && nameMatchesStrong(profileName, accName)) {
            status = com.example.FieldFinder.Enum.BankReviewStatus.APPROVED;
        } else {
            status = com.example.FieldFinder.Enum.BankReviewStatus.PENDING_REVIEW;
            if (!verified) {
                note = "Chưa tra cứu được tên chủ TK — chờ duyệt.";
            } else if (tokenOverlap(profileName, accName) == 0) {
                note = "Tên TK \"" + accName + "\" khác hoàn toàn tên hồ sơ \"" + profileName
                        + "\" — nghi khác người, cần xét.";
            } else {
                note = "Tên TK \"" + accName + "\" khớp một phần tên hồ sơ \"" + profileName
                        + "\" — cần xét.";
            }
        }
        acc.setReviewStatus(status);
        acc.setReviewNote(note);
        BankAccount saved = bankAccountRepository.save(acc);

        // Đổi TK luôn báo user (phát hiện đổi lén); lệch tên thì báo thêm admin.
        notifyBankChanged(user, saved);
        if (status == com.example.FieldFinder.Enum.BankReviewStatus.PENDING_REVIEW) {
            notifyAdminsPendingReview(user, saved);
        }
        return saved;
    }

    @Override
    public List<BankAccount> listPendingReview() {
        return bankAccountRepository.findByReviewStatusOrderByUpdatedAtDesc(
                com.example.FieldFinder.Enum.BankReviewStatus.PENDING_REVIEW);
    }

    @Override
    @Transactional
    public BankAccount review(UUID bankAccountId, boolean approve, String note) {
        BankAccount acc = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy tài khoản ngân hàng!"));
        acc.setReviewStatus(approve
                ? com.example.FieldFinder.Enum.BankReviewStatus.APPROVED
                : com.example.FieldFinder.Enum.BankReviewStatus.REJECTED);
        acc.setReviewNote(note);
        acc.setUpdatedAt(LocalDateTime.now());
        BankAccount saved = bankAccountRepository.save(acc);
        if (saved.getUser() != null) {
            try {
                notificationService.notify(saved.getUser().getUserId(), "BANK_REVIEW_RESULT",
                        approve ? "TK nhận tiền đã được duyệt" : "TK nhận tiền bị từ chối",
                        approve ? "Tài khoản nhận tiền của bạn đã được duyệt, có thể nhận tiền."
                                : ("Tài khoản nhận tiền bị từ chối" + (note != null ? ": " + note : "") + "."),
                        "BANK", saved.getBankAccountId().toString());
            } catch (Exception ignored) {}
        }
        return saved;
    }

    private void notifyBankChanged(User user, BankAccount acc) {
        boolean pending = acc.getReviewStatus() == com.example.FieldFinder.Enum.BankReviewStatus.PENDING_REVIEW;
        String masked = maskAcc(acc.getAccountNumber());
        String label = (acc.getBankName() != null ? acc.getBankName() + " " : "") + masked;
        try {
            notificationService.notify(user.getUserId(), "BANK_ACCOUNT_CHANGED",
                    "Tài khoản nhận tiền đã thay đổi",
                    "TK nhận tiền vừa đổi thành " + label + (pending ? " (đang chờ duyệt)." : "."),
                    "BANK", acc.getBankAccountId().toString());
        } catch (Exception ignored) {}
        try {
            if (user.getEmail() != null) {
                emailService.send(user.getEmail(), "Tài khoản nhận tiền đã thay đổi",
                        "Bạn vừa đặt TK nhận tiền: " + label
                                + (pending ? ". TK đang CHỜ DUYỆT do tên lệch hồ sơ; hệ thống sẽ xét sớm." : ".")
                                + " Nếu KHÔNG phải bạn, hãy đổi mật khẩu/PIN ngay.");
            }
        } catch (Exception ignored) {}
    }

    private void notifyAdminsPendingReview(User user, BankAccount acc) {
        try {
            for (User admin : userRepository.findByRole(User.Role.ADMIN)) {
                notificationService.notify(admin.getUserId(), "BANK_REVIEW_PENDING",
                        "TK nhận tiền cần duyệt",
                        "User " + (user.getName() != null ? user.getName() : user.getUserId())
                                + " thêm TK " + maskAcc(acc.getAccountNumber()) + " — tên lệch hồ sơ, cần xét.",
                        "BANK", acc.getBankAccountId().toString());
            }
        } catch (Exception ignored) {}
    }

    private static String maskAcc(String acc) {
        if (acc == null || acc.length() <= 4) return acc;
        return "*".repeat(acc.length() - 4) + acc.substring(acc.length() - 4);
    }

    @Override
    @Transactional
    public BankAccount setDefault(UUID userId, UUID bankAccountId) {
        BankAccount target = bankAccountRepository.findById(bankAccountId)
                .filter(b -> b.getUser() != null && userId.equals(b.getUser().getUserId()))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy tài khoản ngân hàng!"));
        for (BankAccount b : bankAccountRepository.findByUser_UserIdOrderByCreatedAtDesc(userId)) {
            boolean shouldBeDefault = b.getBankAccountId().equals(bankAccountId);
            if (b.isDefault() != shouldBeDefault) {
                b.setDefault(shouldBeDefault);
                bankAccountRepository.save(b);
            }
        }
        return target;
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID bankAccountId) {
        BankAccount target = bankAccountRepository.findById(bankAccountId)
                .filter(b -> b.getUser() != null && userId.equals(b.getUser().getUserId()))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy tài khoản ngân hàng!"));
        bankAccountRepository.delete(target);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Title phổ biến cần loại khỏi tên trước khi so token (đã chuẩn hóa: bỏ dấu, in hoa). */
    private static final java.util.Set<String> NAME_TITLES =
            java.util.Set.of("MR", "MRS", "MS", "ONG", "BA", "ANH", "CHI", "CO", "CHU", "BAC");

    /** Tách tên (đã normalize) thành tập token, bỏ title. */
    private static java.util.Set<String> nameTokens(String normalized) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (normalized == null) return set;
        for (String t : normalized.split("\\s+")) {
            if (!t.isBlank() && !NAME_TITLES.contains(t)) set.add(t);
        }
        return set;
    }

    /**
     * Khớp mạnh (auto-duyệt): tập token nhỏ ⊆ tập token lớn VÀ tập nhỏ có ≥ 2 token.
     * → "MINH TRIET" khớp "HUYNH MINH TRIET" (thiếu họ vẫn ok); chặn 1 token trùng tình cờ.
     */
    private static boolean nameMatchesStrong(String a, String b) {
        java.util.Set<String> ta = nameTokens(a);
        java.util.Set<String> tb = nameTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        java.util.Set<String> small = ta.size() <= tb.size() ? ta : tb;
        java.util.Set<String> large = ta.size() <= tb.size() ? tb : ta;
        if (small.size() < 2) return false;
        return large.containsAll(small);
    }

    /** Số token chung giữa hai tên — 0 = nghi khác người, >0 = khớp một phần. */
    private static int tokenOverlap(String a, String b) {
        java.util.Set<String> ta = nameTokens(a);
        ta.retainAll(nameTokens(b));
        return ta.size();
    }

    /** Chuẩn hóa tên chủ TK: bỏ dấu, in hoa, gộp khoảng trắng — khớp định dạng ngân hàng. */
    private static String normalizeName(String s) {
        if (s == null) return null;
        String noDiacritics = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return noDiacritics.toUpperCase().replaceAll("\\s+", " ").trim();
    }
}
