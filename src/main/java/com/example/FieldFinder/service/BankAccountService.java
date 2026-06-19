package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.BankAccountRequestDTO;
import com.example.FieldFinder.entity.BankAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountService {

    /** Danh sách TK ngân hàng của user (mới nhất trước). */
    List<BankAccount> listMine(UUID userId);

    /** TK mặc định để nhận hoàn tiền; rỗng nếu user chưa đăng ký. */
    Optional<BankAccount> getDefault(UUID userId);

    /**
     * Thêm mới hoặc cập nhật TK. TK vừa lưu trở thành mặc định,
     * các TK khác của user bị bỏ cờ default.
     */
    BankAccount saveOrUpdate(UUID userId, BankAccountRequestDTO dto);

    /** Đặt một TK đã có làm mặc định. */
    BankAccount setDefault(UUID userId, UUID bankAccountId);

    void delete(UUID userId, UUID bankAccountId);
}
