package com.example.FieldFinder.entity;

import com.example.FieldFinder.entity.Booking.PaymentStatus; // Import enum
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "Payment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PaymentId")
    private Long paymentId;

    @ManyToOne
    @JoinColumn(name = "BookingId", nullable = true)
    private Booking booking;

    @ManyToOne
    @JoinColumn(name = "OrderId", nullable = true)
    private Order order;

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
    private PaymentStatus paymentStatus;

    @Column(name = "CheckoutUrl")
    private String checkoutUrl;

    @Column(name = "TransactionId") // paymentLinkId của PayOS
    private String transactionId;

    @Column(name = "CreatedAt")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum PaymentMethod {
        CASH, BANK
    }
}