package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BookingServiceImpl implements BookingService {
    private final BookingRepository bookingRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    public BookingServiceImpl(BookingRepository bookingRepository,
                              BookingDetailRepository bookingDetailRepository,
                              PitchRepository pitchRepository,
                              UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingDetailRepository = bookingDetailRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
        this.restTemplate = new RestTemplate();
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
    public List<PitchBookedSlotsDTO> getAllBookedTimeSlots(LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByBooking_BookingDate(bookingDate);

        // Group by pitchId and collect slot lists
        Map<UUID, List<Integer>> grouped = bookingDetails.stream()
                .collect(Collectors.groupingBy(
                        bd -> bd.getPitch().getPitchId(),
                        Collectors.mapping(BookingDetail::getSlot, Collectors.toList())
                ));

        // Map to a DTO list
        return grouped.entrySet().stream()
                .map(entry -> new PitchBookedSlotsDTO(entry.getKey(),
                        entry.getValue().stream().distinct().collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getAvailablePitches(LocalDate bookingDate, List<Integer> requestedSlots, String pitchType) {
        // 1. Lấy danh sách tất cả pitchId từ DB, có lọc theo pitchType
        List<Pitch> allPitches = pitchRepository.findAll();
        List<String> allPitchIds = allPitches.stream()
                .filter(p -> "ALL".equalsIgnoreCase(pitchType) || p.getType().name().equalsIgnoreCase(pitchType))
                .map(p -> p.getPitchId().toString())
                .collect(Collectors.toList());

        // 2. Lấy danh sách slot đã đặt cho ngày yêu cầu
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByBooking_BookingDate(bookingDate);
        Map<UUID, List<Integer>> bookedSlotsByPitch = bookingDetails.stream()
                .collect(Collectors.groupingBy(
                        bd -> bd.getPitch().getPitchId(),
                        Collectors.mapping(BookingDetail::getSlot, Collectors.toList())
                ));

        // 3. Lọc sân trống (không có slot nào trong requestedSlots bị đặt)
        return allPitchIds.stream()
                .filter(pitchId -> {
                    List<Integer> bookedSlots = bookedSlotsByPitch.getOrDefault(UUID.fromString(pitchId), Collections.emptyList());
                    // Sân trống nếu không có slot nào trong requestedSlots bị đặt
                    return requestedSlots.stream().noneMatch(bookedSlots::contains);
                })
                .collect(Collectors.toList());
    }

    @Override
    public boolean isPitchAvailable(UUID pitchId, LocalDate date, List<Integer> slotList, String pitchType) {
        List<String> availablePitches = (List<String>) getAvailablePitches(date, slotList, pitchType);
        return availablePitches.contains(pitchId.toString());
    }

    @Override
    @Transactional
    public Booking createBooking(BookingRequestDTO bookingRequest) {
        User user = userRepository.findById(bookingRequest.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Pitch pitch = pitchRepository.findById(bookingRequest.getPitchId())
                .orElseThrow(() -> new RuntimeException("Pitch not found"));

//        BigDecimal totalPrice = bookingRequest.getBookingDetails().stream()
//                .map(BookingRequestDTO.BookingDetailDTO::getPriceDetail)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPrice = bookingRequest.getTotalPrice();
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

    @Transactional
    public ResponseEntity<String> updatePaymentStatus(UUID bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        Booking.PaymentStatus newStatus;
        try {
            newStatus = Booking.PaymentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid payment status. Allowed values: PENDING, PAID, REFUNDED");
        }

        booking.setPaymentStatus(newStatus);
        bookingRepository.save(booking);

        return ResponseEntity.ok("Payment status updated successfully");
    }


    @Override
    @Transactional
    public ResponseEntity<String> updateBookingStatus(UUID bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        Booking.BookingStatus newStatus;
        try {
            newStatus = Booking.BookingStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid booking status. Allowed values: PENDING, CONFIRMED, CANCELED");
        }

        booking.setStatus(newStatus);
        bookingRepository.save(booking);

        return ResponseEntity.ok("Booking status updated successfully");
    }


    @Override
    public List<BookingResponseDTO> getBookingsByUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Booking> bookings = bookingRepository.findByUser(user);

        return bookings.stream()
                .map(BookingResponseDTO::fromEntity)  // convert each booking to DTO
                .collect(Collectors.toList());
    }

    @Override
    public List<BookingResponseDTO> getAllBookings() {
        List<Booking> bookings = bookingRepository.findAll();
        return bookings.stream()
                .map(BookingResponseDTO::fromEntity)
                .collect(Collectors.toList());
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