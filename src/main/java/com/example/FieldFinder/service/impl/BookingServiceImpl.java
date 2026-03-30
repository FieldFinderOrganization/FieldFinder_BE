package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.config.RabbitMQConfig;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.dto.res.ProviderBookingResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.PitchRedisLockService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RabbitTemplate rabbitTemplate;
    private final PitchRedisLockService pitchRedisLockService;

    public BookingServiceImpl(BookingRepository bookingRepository,
                              BookingDetailRepository bookingDetailRepository,
                              PitchRepository pitchRepository,
                              UserRepository userRepository,
                              RestTemplate restTemplate,
                              RabbitTemplate rabbitTemplate,
                              PitchRedisLockService pitchRedisLockService) {
        this.bookingRepository = bookingRepository;
        this.bookingDetailRepository = bookingDetailRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
        this.restTemplate = restTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.pitchRedisLockService = pitchRedisLockService;
    }

    @Override
    public List<Integer> getBookedTimeSlots(UUID pitchId, LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByPitch_PitchIdAndBooking_BookingDate(pitchId, bookingDate);
        return bookingDetails.stream()
                .map(bd -> bd.getTimeSlot().getSlotId()) // SỬA Ở ĐÂY: Trích xuất ID từ Entity TimeSlot
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<PitchBookedSlotsDTO> getAllBookedTimeSlots(LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByBooking_BookingDate(bookingDate);

        Map<UUID, List<Integer>> grouped = bookingDetails.stream()
                .collect(Collectors.groupingBy(
                        bd -> bd.getPitch().getPitchId(),
                        Collectors.mapping(bd -> bd.getTimeSlot().getSlotId(), Collectors.toList()) // SỬA Ở ĐÂY
                ));

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
                .orElseThrow(() -> new RuntimeException("User not found!"));
        Pitch pitch = pitchRepository.findById(bookingRequest.getPitchId())
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));

        LocalDate bookingDate = bookingRequest.getBookingDate();
        UUID pitchId = (bookingRequest.getPitchId());
        String userId = bookingRequest.getUserId().toString();

        List<Integer> requestedSlots = bookingRequest.getBookingDetails().stream()
                .map(detail -> detail.getTimeSlot().getSlotId())
                .collect(Collectors.toList());

        boolean isLocked = pitchRedisLockService.lockSlots(pitchId, bookingDate, requestedSlots, userId);

        if (!isLocked) {
            throw new RuntimeException("Rất tiếc! Một trong những khung giờ bạn chọn vừa bị người khác đặt mất. Vui lòng chọn giờ khác.");
        }

        try {
            Booking booking = Booking.builder()
                    .user(user)
                    .bookingDate(bookingRequest.getBookingDate())
                    .status(Booking.BookingStatus.PENDING)
                    .paymentStatus(Booking.PaymentStatus.PENDING)
                    .totalPrice(bookingRequest.getTotalPrice())
                    .bookingDetails(new ArrayList<>())
                    .build();

            List<BookingDetail> details = bookingRequest.getBookingDetails().stream().map(detailDTO -> {
                BookingDetail detail = new BookingDetail();
                detail.setBooking(booking);
                detail.setPitch(pitch);
                detail.setTimeSlot(detailDTO.getTimeSlot());
                detail.setName(detailDTO.getName());
                detail.setPriceDetail(detailDTO.getPriceDetail());
                return detail;
            }).collect(Collectors.toList());

            booking.setBookingDetails(details);


            Booking savedBooking = bookingRepository.save(booking);

            // BẮN MESSAGE SANG RABBITMQ
            // Gửi ID của đơn đặt sân sang Queue thay vì gửi email trực tiếp để tối ưu thời gian phản hồi
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE, RabbitMQConfig.BOOKING_EMAIL_ROUTING_KEY, savedBooking.getBookingId().toString());

            return savedBooking;
        } catch (Exception e) {
            for (Integer slotId : requestedSlots) {
                pitchRedisLockService.unlockSlot(pitchId, bookingDate, slotId, userId);
            }
            throw e;
        }
    }

    @Transactional
    public ResponseEntity<String> updatePaymentStatus(UUID bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found!"));

        Booking.PaymentStatus newStatus;
        try {
            newStatus = Booking.PaymentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid payment status. Allowed values: PENDING, PAID, REFUNDED!");
        }

        booking.setPaymentStatus(newStatus);
        bookingRepository.save(booking);

        return ResponseEntity.ok("Payment status updated successfully!");
    }

    @Override
    public List<ProviderBookingResponseDTO> getBookingsByProviderId(UUID providerId) {
        List<Booking> bookings = bookingRepository.findByProviderId(providerId);

        return bookings.stream().map(booking -> {
            String paymentMethod = "Chưa thanh toán";
            String pitchName = "Không xác định";
            List<Integer> slots = new ArrayList<>();

            if (booking.getBookingDetails() != null && !booking.getBookingDetails().isEmpty()) {
                BookingDetail firstDetail = booking.getBookingDetails().get(0);
                Pitch pitch = pitchRepository.findById(firstDetail.getPitchId()).orElse(null);
                if (pitch != null) pitchName = pitch.getName();

                slots = booking.getBookingDetails().stream()
                        .map(BookingDetail::getSlot)
                        .collect(Collectors.toList());
            }

            User customer = booking.getUser();
            String userName = (customer != null) ? customer.getName() : "Khách hàng";
            UUID customerId = (customer != null) ? customer.getUserId() : null;

            return ProviderBookingResponseDTO.builder()
                    .bookingId(booking.getBookingId())
                    .bookingDate(booking.getBookingDate())
                    .status(booking.getStatus().name())
                    .paymentStatus(booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : "PENDING")
                    .totalPrice(booking.getTotalPrice())
                    .providerId(providerId)
                    .paymentMethod(paymentMethod)
                    .userId(customerId)
                    .userName(userName)
                    .pitchName(pitchName)
                    .slots(slots)
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ResponseEntity<String> updateBookingStatus(UUID bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found!"));

        Booking.BookingStatus newStatus;
        try {
            newStatus = Booking.BookingStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid booking status. Allowed values: PENDING, CONFIRMED, CANCELED!");
        }

        booking.setStatus(newStatus);
        bookingRepository.save(booking);

        return ResponseEntity.ok("Booking status updated successfully!");
    }


    @Override
    public List<ProviderBookingResponseDTO> getBookingsByUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found!"));

        List<Booking> bookings = bookingRepository.findByUser(user);

        return bookings.stream().map(booking -> {
            String pitchName = "Không xác định";
            List<Integer> slots = new ArrayList<>();

            if (booking.getBookingDetails() != null && !booking.getBookingDetails().isEmpty()) {
                BookingDetail firstDetail = booking.getBookingDetails().get(0);
                Pitch pitch = pitchRepository.findById(firstDetail.getPitchId()).orElse(null);
                if (pitch != null) pitchName = pitch.getName();

                slots = booking.getBookingDetails().stream()
                        .map(BookingDetail::getSlot)
                        .collect(Collectors.toList());
            }

            String providerName = "Không xác định";

            return ProviderBookingResponseDTO.builder()
                    .bookingId(booking.getBookingId())
                    .bookingDate(booking.getBookingDate())
                    .status(booking.getStatus().name())
                    .paymentStatus(booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : "PENDING")
                    .totalPrice(booking.getTotalPrice())
                    .pitchName(pitchName)
                    .providerName(providerName) // Backend trả về
                    .slots(slots)
                    .build();
        }).collect(Collectors.toList());
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