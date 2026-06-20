package com.example.FieldFinder.dto.res;

/** Kết quả tra cứu tên chủ TK cho FE. */
public record BankAccountLookupResponseDTO(
        boolean found,
        String accountName,
        String message
) {
}
