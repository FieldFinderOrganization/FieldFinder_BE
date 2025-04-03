package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Bookings")
@Data
@Builder
public class Booking {
    @Setter
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "BookingId")
    private UUID bookingId;

    @ManyToOne
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "BookingDate", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "StartTime", nullable = false)
    private LocalTime startTime;

    @Column(name = "EndTime", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "PaymentStatus", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "TotalPrice", nullable = false)
    private BigDecimal totalPrice;

    public enum BookingStatus {
        PENDING, CONFIRMED, CANCELED
    }

    public enum PaymentStatus {
        PENDING, PAID, REFUNDED
    }
}
