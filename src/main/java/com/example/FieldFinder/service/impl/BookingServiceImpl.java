package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

        // Map to DTO list
        return grouped.entrySet().stream()
                .map(entry -> new PitchBookedSlotsDTO(entry.getKey(),
                        entry.getValue().stream().distinct().collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getAvailablePitches(LocalDate bookingDate, List<Integer> requestedSlots, String pitchType) {
        // 1. Gọi API nội bộ để lấy danh sách pitch đã được đặt slot trong ngày đó
        String url = "http://localhost:8080/api/bookings/slots/all?date=" + bookingDate;
        ResponseEntity<PitchBookedSlotsDTO[]> response = restTemplate.getForEntity(url, PitchBookedSlotsDTO[].class);
        PitchBookedSlotsDTO[] bookedSlots = response.getBody();

        // 2. Lấy danh sách tất cả pitchId từ DB, có lọc theo pitchType nếu được chỉ định
        List<String> allPitchIds = pitchRepository.findAll().stream()
                .filter(p -> pitchType == null || pitchType.isBlank() ||
                        p.getType().name().equalsIgnoreCase(pitchType))
                .map(p -> p.getPitchId().toString())
                .collect(Collectors.toList());

        // 3. Lọc ra pitch có slot trùng với requestedSlots
        Set<String> bookedPitches = Arrays.stream(bookedSlots)
                .filter(p -> p.getBookedSlots().stream().anyMatch(requestedSlots::contains))
                .map(p -> p.getPitchId().toString())
                .collect(Collectors.toSet());

        // 4. Trả về danh sách sân còn trống (không bị trùng slot và đúng loại sân)
        return allPitchIds.stream()
                .filter(pitchId -> !bookedPitches.contains(pitchId))
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