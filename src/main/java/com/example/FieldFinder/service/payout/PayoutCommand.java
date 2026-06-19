package com.example.FieldFinder.service.payout;

/** Lệnh chi một khoản về TK ngân hàng. */
public record PayoutCommand(
        String referenceId,
        long amountVnd,
        String description,
        String toBin,
        String toAccountNumber
) {
}
