package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Booking.BookingStatus;
import com.example.FieldFinder.entity.Booking.PaymentStatus;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.BookingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingServiceImpl implements BookingService {
    private final BookingRepository bookingRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;

    public BookingServiceImpl(BookingRepository bookingRepository,
                              BookingDetailRepository bookingDetailRepository,
                              PitchRepository pitchRepository,
                              UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingDetailRepository = bookingDetailRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
    }
    @Override
    public List<Integer> getBookedTimeSlots(UUID pitchId, LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByPitch_PitchIdAndBooking_BookingDate(pitchId, bookingDate);
        return bookingDetails.stream()
                .map(BookingDetail::getSlot)
                .distinct()
                .collect(Collectors.toList());
    }


    @Override
    @Transactional
    public Booking createBooking(BookingRequestDTO bookingRequest) {
        User user = userRepository.findById(bookingRequest.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Pitch pitch = pitchRepository.findById(bookingRequest.getPitchId())
                .orElseThrow(() -> new RuntimeException("Pitch not found"));

        BigDecimal totalPrice = bookingRequest.getBookingDetails().stream()
                .map(BookingRequestDTO.BookingDetailDTO::getPriceDetail)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Booking booking = Booking.builder()
                .user(user)
                .bookingDate(bookingRequest.getBookingDate())
                .status(Booking.BookingStatus.PENDING)
                .paymentStatus(Booking.PaymentStatus.PENDING)
                .totalPrice(totalPrice)
                .build();

        bookingRepository.save(booking);

        List<BookingDetail> details = bookingRequest.getBookingDetails().stream().map(detailDTO -> {
            BookingDetail detail = new BookingDetail();
            detail.setBooking(booking);
            detail.setPitch(pitch);
            detail.setSlot(detailDTO.getSlot()); // NEW: use slot number
            detail.setName(detailDTO.getName());
            detail.setPriceDetail(detailDTO.getPriceDetail());
            return detail;
        }).collect(Collectors.toList());


        bookingDetailRepository.saveAll(details);
        return booking;
    }


    @Override
    public Booking updateBookingStatus(UUID bookingId, String status) {
        return null;
    }

    @Override
    public List<Booking> getBookingsByUser(UUID userId) {
        return List.of();
    }

    @Override
    public Booking getBookingDetails(UUID bookingId) {
        return null;
    }

    @Override
    public void cancelBooking(UUID bookingId) {

    }

    @Override
    public BigDecimal calculateTotalPrice(UUID bookingId) {
        return null;
    }
}