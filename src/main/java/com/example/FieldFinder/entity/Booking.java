package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingDetail> bookingDetails = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "BookingDate", nullable = false)
    private LocalDate bookingDate;

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
