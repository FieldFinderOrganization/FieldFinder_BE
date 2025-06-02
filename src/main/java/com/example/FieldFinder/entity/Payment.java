package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "Payments")
@Data
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "PaymentId")
    private UUID paymentId;

    @ManyToOne
    @JoinColumn(name = "BookingId", nullable = false)
    private Booking booking;

    @ManyToOne
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "Amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "PaymentMethod", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "PaymentStatus", nullable = false)
    private Booking.PaymentStatus paymentStatus;

    @Column(name = "TransactionId", unique = true, nullable = false)
    private String transactionId;

    public enum PaymentMethod {
        BANK, TIENMAT
    }
}
