package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProviderBookingResponseDTO {
    private UUID userId;
    private String userName;

    private UUID providerUserId;

    private UUID bookingId;
    private LocalDate bookingDate;
    private String status;
    private String paymentStatus;
    private BigDecimal totalPrice;
    private UUID providerId;
    private UUID pitchId;

    private String paymentMethod;
    private String providerName;
    private String pitchName;

    private String pitchImageUrl;
    private List<Integer> slots;
    private List<String> slotsName;

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    /** Hạn thanh toán của đơn PENDING (Dynamic Hold). Null nếu đơn không còn chờ thanh toán. */
    private LocalDateTime paymentDeadline;

    /** USER / PROVIDER / SYSTEM — null nếu đơn chưa hủy. */
    private String cancelledBy;
    private String cancelReason;
    private LocalDateTime cancelledAt;

    /** Khóa lịch thủ công: MAINTENANCE / OFFLINE_BOOKING. Null = đơn đặt thường. */
    private String blockType;
    /** Ghi chú chủ sân khi khóa (tên/SĐT/cọc khách đặt ngoài app). */
    private String providerNotes;
}