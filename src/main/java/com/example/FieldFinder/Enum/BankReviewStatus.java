package com.example.FieldFinder.Enum;

/** Trạng thái duyệt TK nhận tiền theo khớp tên (chống đổi TK sang danh tính khác). */
public enum BankReviewStatus {
    APPROVED,        // tên TK khớp hồ sơ ⇒ auto-duyệt, được nhận tiền
    PENDING_REVIEW,  // tên lệch / chưa tra cứu được ⇒ chờ admin xét, TẠM chưa payout
    REJECTED         // admin từ chối ⇒ không được nhận tiền
}
