package com.example.FieldFinder.event;

import com.example.FieldFinder.entity.Discount;

/**
 * Phát khi đã tạo (lưu) một mã giảm giá. Việc gửi thông báo "mã mới" cho user
 * được xử lý async sau khi transaction commit (xem {@link DiscountCreatedListener}).
 */
public class DiscountCreatedEvent {

    private final Discount discount;

    public DiscountCreatedEvent(Discount discount) {
        this.discount = discount;
    }

    public Discount getDiscount() {
        return discount;
    }
}
