package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.config.RabbitMQConfig;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.dto.res.ProviderBookingResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.PitchRedisLockService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {
    private final BookingRepository bookingRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final PitchRedisLockService pitchRedisLockService;
    private final EntityManager entityManager;
    private final PaymentRepository paymentRepository;
    private final ProviderRepository providerRepository;
    private final EmailService emailService;
    private final RefundService refundService;

    private static final long BOOKING_REFUND_MIN_MINUTES_BEFORE = 10;

    @Override
    public List<Integer> getBookedTimeSlots(UUID pitchId, LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByPitchAndDateExcludingStatuses(
                pitchId, bookingDate, List.of(BookingStatus.CANCELED));
        return bookingDetails.stream()
                .map(bd -> bd.getTimeSlot().getSlotId())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<PitchBookedSlotsDTO> getAllBookedTimeSlots(LocalDate bookingDate) {
        List<BookingDetail> bookingDetails = bookingDetailRepository.findByBookingDateExcludingStatuses(
                bookingDate, List.of(BookingStatus.CANCELED));

        Map<UUID, List<Integer>> grouped = bookingDetails.stream()
                .collect(Collectors.groupingBy(
                        bd -> bd.getPitch().getPitchId(),
                        Collectors.mapping(bd -> bd.getTimeSlot().getSlotId(), Collectors.toList())));

        return grouped.entrySet().stream()
                .map(entry -> new PitchBookedSlotsDTO(entry.getKey(),
                        entry.getValue().stream().distinct().collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getAvailablePitches(LocalDate bookingDate, List<Integer> requestedSlots, String pitchType) {
        String url = "http://localhost:8080/api/bookings/slots/all?date=" + bookingDate;
        ResponseEntity<PitchBookedSlotsDTO[]> response = restTemplate.getForEntity(url, PitchBookedSlotsDTO[].class);
        PitchBookedSlotsDTO[] bookedSlots = response.getBody();

        List<String> allPitchIds = pitchRepository.findAll().stream()
                .filter(p -> pitchType == null || pitchType.isBlank() ||
                        p.getType().name().equalsIgnoreCase(pitchType))
                .map(p -> p.getPitchId().toString())
                .toList();

        Set<String> bookedPitches = Arrays.stream(bookedSlots)
                .filter(p -> p.getBookedSlots().stream().anyMatch(requestedSlots::contains))
                .map(p -> p.getPitchId().toString())
                .collect(Collectors.toSet());

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
                .map(BookingRequestDTO.BookingDetailDTO::getSlot)
                .collect(Collectors.toList());

        boolean isLocked = pitchRedisLockService.lockSlots(pitchId, bookingDate, requestedSlots, userId);

        if (!isLocked) {
            throw new RuntimeException(
                    "Rất tiếc! Một trong những khung giờ bạn chọn vừa bị người khác đặt mất. Vui lòng chọn giờ khác.");
        }

        try {
            Booking booking = Booking.builder()
                    .user(user)
                    .bookingDate(bookingRequest.getBookingDate())
                    .createdAt(LocalDateTime.now())
                    .status(BookingStatus.PENDING)
                    .paymentStatus(PaymentStatus.PENDING)
                    .totalPrice(bookingRequest.getTotalPrice())
                    .build();

            if (bookingRequest.getPaymentMethod() != null &&
                    bookingRequest.getPaymentMethod().equalsIgnoreCase("CASH")) {
                booking.setStatus(BookingStatus.CONFIRMED);
                booking.setPaymentStatus(PaymentStatus.PAID);
            }

            List<BookingDetail> details = bookingRequest.getBookingDetails().stream().map(detailDTO -> {
                BookingDetail detail = new BookingDetail();
                detail.setBooking(booking);
                detail.setPitch(pitch);

                TimeSlot timeSlot = entityManager.getReference(TimeSlot.class, detailDTO.getSlot());
                detail.setTimeSlot(timeSlot);

                detail.setName(detailDTO.getName());
                detail.setPriceDetail(detailDTO.getPriceDetail());
                return detail;
            }).collect(Collectors.toList());

            booking.setBookingDetails(details);

            Booking savedBooking = bookingRepository.save(booking);

            if (bookingRequest.getPaymentMethod() != null &&
                    bookingRequest.getPaymentMethod().equalsIgnoreCase("CASH")) {
                Payment payment = Payment.builder()
                        .booking(savedBooking)
                        .user(user)
                        .amount(bookingRequest.getTotalPrice())
                        .paymentMethod(PaymentMethod.CASH)
                        .paymentStatus(PaymentStatus.PAID)
                        .createdAt(LocalDateTime.now())
                        .paidAt(LocalDateTime.now())
                        .build();
                paymentRepository.save(payment);
            }

            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE, RabbitMQConfig.BOOKING_EMAIL_ROUTING_KEY,
                    savedBooking.getBookingId().toString());

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

        PaymentStatus newStatus;
        try {
            newStatus = PaymentStatus.valueOf(status.toUpperCase());
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

        Provider provider = providerRepository.findById(providerId).orElse(null);
        UUID providerUserId = (provider != null && provider.getUser() != null) ? provider.getUser().getUserId() : null;

        return bookings.stream().map(booking -> {
            String pitchName = "Không xác định";
            List<Integer> slots = new ArrayList<>();

            if (booking.getBookingDetails() != null && !booking.getBookingDetails().isEmpty()) {
                BookingDetail firstDetail = booking.getBookingDetails().getFirst();
                Pitch pitch = pitchRepository.findById(firstDetail.getPitchId()).orElse(null);
                if (pitch != null)
                    pitchName = pitch.getName();

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
                    .providerUserId(providerUserId)
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

        BookingStatus newStatus;
        try {
            newStatus = BookingStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body("Invalid booking status. Allowed values: PENDING, CONFIRMED, CANCELED!");
        }

        booking.setStatus(newStatus);
        bookingRepository.save(booking);

        return ResponseEntity.ok("Booking status updated successfully!");
    }

    @Override
    public List<ProviderBookingResponseDTO> getBookingsByUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found!"));

        List<Booking> bookings = bookingRepository.findByUserWithDetails(user);

        if (bookings.isEmpty())
            return new ArrayList<>();

        List<UUID> bookingIds = bookings.stream().map(Booking::getBookingId).toList();
        List<Payment> allPayments = paymentRepository.findAllByBookingIds(bookingIds);

        Map<UUID, String> paymentMap = allPayments.stream()
                .filter(p -> p.getBooking() != null)
                .collect(Collectors.toMap(
                        p -> p.getBooking().getBookingId(),
                        p -> p.getPaymentMethod() != null ? p.getPaymentMethod().name() : "PENDING",
                        (existing, replacement) -> existing));

        return bookings.stream().map(booking -> {
            String pitchName = "Không xác định";
            String providerName = "Không xác định";
            String pitchImageUrl = null;
            UUID providerId = null;
            UUID pitchId = null;
            UUID providerUserId = null;
            List<Integer> slots = new ArrayList<>();

            if (booking.getBookingDetails() != null && !booking.getBookingDetails().isEmpty()) {
                BookingDetail firstDetail = booking.getBookingDetails().getFirst();
                Pitch pitch = firstDetail.getPitch();
                if (pitch != null) {
                    pitchName = pitch.getName();
                    pitchId = pitch.getPitchId();
                    if (pitch.getImageUrls() != null && !pitch.getImageUrls().isEmpty()) {
                        pitchImageUrl = pitch.getImageUrls().getFirst();
                    }
                    if (pitch.getProviderAddress() != null && pitch.getProviderAddress().getProvider() != null) {
                        var provider = pitch.getProviderAddress().getProvider();
                        providerId = provider.getProviderId();
                        if (provider.getUser() != null) {
                            providerName = provider.getUser().getName();
                            providerUserId = provider.getUser().getUserId();
                        } else {
                            providerName = "Không xác định";
                        }
                    }
                }
                slots = booking.getBookingDetails().stream()
                        .map(BookingDetail::getSlot)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }

            String paymentMethod = paymentMap.getOrDefault(booking.getBookingId(), "PENDING");

            LocalDateTime paidAt = allPayments.stream()
                    .filter(p -> p.getBooking() != null && p.getBooking().getBookingId().equals(booking.getBookingId()))
                    .filter(p -> p.getPaymentStatus() == PaymentStatus.PAID)
                    .map(Payment::getPaidAt)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            return ProviderBookingResponseDTO.builder()
                    .bookingId(booking.getBookingId())
                    .bookingDate(booking.getBookingDate())
                    .status(booking.getStatus() != null ? booking.getStatus().name() : "PENDING")
                    .paymentStatus(booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : "PENDING")
                    .totalPrice(booking.getTotalPrice())
                    .pitchName(pitchName)
                    .pitchId(pitchId)
                    .pitchImageUrl(pitchImageUrl)
                    .providerName(providerName)
                    .providerId(providerId)
                    .providerUserId(providerUserId)
                    .paymentMethod(paymentMethod)
                    .userId(user.getUserId())
                    .userName(user.getName())
                    .slots(slots)
                    .createdAt(booking.getCreatedAt())
                    .paidAt(paidAt)
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
    @Transactional
    public void cancelBookingByUser(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found!"));

        if (!booking.getUser().getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền hủy đặt sân này!");
        }

        boolean isPending = booking.getStatus() == BookingStatus.PENDING;
        boolean isPaidConfirmed = booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getPaymentStatus() == PaymentStatus.PAID;

        if (!isPending && !isPaidConfirmed) {
            throw new RuntimeException("Trạng thái đặt sân không cho phép hủy!");
        }

        // Validate cửa sổ ≥10p trước slot đầu (chỉ áp với booking đã thanh toán)
        if (isPaidConfirmed) {
            LocalDateTime earliestStart = earliestSlotStart(booking);
            if (earliestStart == null) {
                throw new RuntimeException("Không xác định được giờ bắt đầu của slot!");
            }
            long minutesUntil = ChronoUnit.MINUTES.between(LocalDateTime.now(), earliestStart);
            if (minutesUntil < BOOKING_REFUND_MIN_MINUTES_BEFORE) {
                throw new RuntimeException(
                        "Phải hủy trước ít nhất " + BOOKING_REFUND_MIN_MINUTES_BEFORE
                                + " phút so với giờ bắt đầu để được hoàn tiền!");
            }
        }

        // Save DB first (within transaction), unlock Redis AFTER commit
        cancelBooking(booking, "User requested cancellation");

        // Phát hành mã hoàn tiền nếu booking đã thanh toán
        if (isPaidConfirmed && booking.getTotalPrice() != null
                && booking.getTotalPrice().signum() > 0) {
            refundService.issueRefundCredit(
                    booking.getUser(),
                    RefundSourceType.BOOKING,
                    booking.getBookingId().toString(),
                    booking.getTotalPrice(),
                    "User cancel booking ≥10m before start");

            paymentRepository
                    .findFirstByBooking_BookingIdOrderByCreatedAtDesc(booking.getBookingId())
                    .ifPresent(p -> {
                        p.setPaymentStatus(PaymentStatus.REFUNDED);
                        p.setProcessedAt(LocalDateTime.now());
                        paymentRepository.save(p);
                    });
            booking.setPaymentStatus(PaymentStatus.REFUNDED);
            bookingRepository.save(booking);
        }

        // Unlock Redis slots only after the transaction commits successfully
        // This prevents double-booking: if DB rollback occurs, Redis lock stays → slot remains locked
        final String userIdStr = userId.toString();
        final LocalDate bookingDate = booking.getBookingDate();
        final List<BookingDetail> details = new ArrayList<>(booking.getBookingDetails());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (BookingDetail detail : details) {
                    if (detail.getPitch() != null && detail.getTimeSlot() != null) {
                        pitchRedisLockService.unlockSlot(
                                detail.getPitch().getPitchId(),
                                bookingDate,
                                detail.getTimeSlot().getSlotId(),
                                userIdStr);
                    }
                }
            }
        });
    }

    @Override
    public BigDecimal calculateTotalPrice(UUID bookingId) {
        return null;
    }

    @Override
    public List<BookingResponseDTO> getBookingsByProvider(UUID providerId) {
        return List.of();
    }

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void processAutomatedBookingManagement() {
        LocalDate today = LocalDate.now();
        LocalTime currentTime = LocalTime.now();

        List<Booking> pendingBookings = bookingRepository.findAllByStatus(BookingStatus.PENDING);

        for (Booking booking : pendingBookings) {
            // 1. Data Cleanup: Canceled old pending records
            if (booking.getBookingDate().isBefore(today)) {
                unlockRedisSlots(booking);
                cancelBooking(booking, "Data Cleanup: Booking date passed");
                continue;
            }

            // Find earliest slot start time
            Optional<LocalTime> earliestSlotStart = booking.getBookingDetails().stream()
                    .map(bd -> bd.getTimeSlot() != null ? bd.getTimeSlot().getStartTime() : null)
                    .filter(Objects::nonNull)
                    .min(LocalTime::compareTo);

            if (earliestSlotStart.isPresent() && booking.getBookingDate().equals(today)) {
                LocalTime startTime = earliestSlotStart.get();
                long minutesUntilStart = ChronoUnit.MINUTES.between(currentTime, startTime);

                // 2. Reminder Email at T-10m
                if (!Boolean.TRUE.equals(booking.getIsReminderSent()) && minutesUntilStart <= 10
                        && minutesUntilStart > 5) {
                    System.out.println("Sending reminder for Booking #" + booking.getBookingId());
                    rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE,
                            RabbitMQConfig.BOOKING_REMINDER_EMAIL_ROUTING_KEY,
                            booking.getBookingId().toString());
                    booking.setIsReminderSent(true);
                    bookingRepository.save(booking);
                }

                // 3. Auto-Cancellation at T-5m
                if (minutesUntilStart <= 5) {
                    unlockRedisSlots(booking);
                    cancelBooking(booking, "Auto-Cancel: Unpaid 5m before start or time passed");
                }
            }
        }
    }

    private void unlockRedisSlots(Booking booking) {
        String userId = booking.getUser().getUserId().toString();
        for (BookingDetail detail : booking.getBookingDetails()) {
            if (detail.getPitch() != null && detail.getTimeSlot() != null) {
                pitchRedisLockService.unlockSlot(
                        detail.getPitch().getPitchId(),
                        booking.getBookingDate(),
                        detail.getTimeSlot().getSlotId(),
                        userId);
            }
        }
    }

    private LocalDateTime earliestSlotStart(Booking booking) {
        if (booking.getBookingDetails() == null) return null;
        return booking.getBookingDetails().stream()
                .map(bd -> bd.getTimeSlot() != null ? bd.getTimeSlot().getStartTime() : null)
                .filter(Objects::nonNull)
                .min(LocalTime::compareTo)
                .map(t -> LocalDateTime.of(booking.getBookingDate(), t))
                .orElse(null);
    }

    private void cancelBooking(Booking booking, String reason) {
        System.out.println("🚫 " + reason + " for Booking #" + booking.getBookingId());
        booking.setStatus(BookingStatus.CANCELED);
        bookingRepository.save(booking);

        List<Payment> payments = paymentRepository
                .findAllByBookingIds(Collections.singletonList(booking.getBookingId()));
        if (payments != null) {
            for (Payment p : payments) {
                if (p.getPaymentStatus() == PaymentStatus.PENDING) {
                    p.setPaymentStatus(PaymentStatus.CANCELED);
                    paymentRepository.save(p);
                }
            }
        }

        try {
            emailService.sendBookingCancellation(booking);
        } catch (Exception e) {
            System.err.println("Lỗi gửi email hủy đặt sân: " + e.getMessage());
        }
    }
}