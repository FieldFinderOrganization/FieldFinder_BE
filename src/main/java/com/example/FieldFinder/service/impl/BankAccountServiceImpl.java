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
        // Trạng thái xác thực theo kết quả tra cứu lần này (verified nếu lookup ok)
        acc.setVerified(verified);
        acc.setDefault(true);
        acc.setUpdatedAt(LocalDateTime.now());
        return bankAccountRepository.save(acc);
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

    /** Chuẩn hóa tên chủ TK: bỏ dấu, in hoa, gộp khoảng trắng — khớp định dạng ngân hàng. */
    private static String normalizeName(String s) {
        if (s == null) return null;
        String noDiacritics = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return noDiacritics.toUpperCase().replaceAll("\\s+", " ").trim();
    }
}
