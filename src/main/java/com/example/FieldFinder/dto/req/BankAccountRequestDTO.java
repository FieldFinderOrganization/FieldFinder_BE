package com.example.FieldFinder.dto.req;

/** Người dùng đăng ký / cập nhật tài khoản ngân hàng nhận hoàn tiền. */
public record BankAccountRequestDTO(
        String bankBin,
        String bankName,
        String accountNumber,
        String accountName
) {
}
