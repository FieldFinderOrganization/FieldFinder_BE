package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "booking_detail")
public class BookingDetail implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long bookingDetailId;

    @ManyToOne
    @JoinColumn(name = "booking", nullable = false)
    private Booking booking;

    private int slot;
    private String name;

    @ManyToOne
    @JoinColumn(name = "pitch_id", nullable = false)
    private Pitch pitch;

    private BigDecimal priceDetail;
}
