package com.example.FieldFinder.dto.req;

/** Tra cứu tên chủ TK trước khi lưu (preview, giống app ngân hàng). */
public record BankAccountLookupRequestDTO(
        String bankBin,
        String accountNumber
) {
}
