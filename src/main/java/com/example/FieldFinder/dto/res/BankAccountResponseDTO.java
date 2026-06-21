package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.BankAccount;

import java.util.UUID;

/** TK ngân hàng trả về FE — che bớt số TK cho an toàn hiển thị. */
public record BankAccountResponseDTO(
        UUID bankAccountId,
        String bankBin,
        String bankName,
        String accountNumber,
        String maskedAccountNumber,
        String accountName,
        boolean isDefault,
        boolean verified,
        String reviewStatus,
        String reviewNote
) {
    public static BankAccountResponseDTO from(BankAccount b) {
        return new BankAccountResponseDTO(
                b.getBankAccountId(),
                b.getBankBin(),
                b.getBankName(),
                b.getAccountNumber(),
                mask(b.getAccountNumber()),
                b.getAccountName(),
                b.isDefault(),
                b.isVerified(),
                b.getReviewStatus() != null ? b.getReviewStatus().name() : null,
                b.getReviewNote()
        );
    }

    private static String mask(String acc) {
        if (acc == null || acc.length() <= 4) return acc;
        int visible = 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < acc.length() - visible; i++) sb.append('*');
        sb.append(acc.substring(acc.length() - visible));
        return sb.toString();
    }
}
