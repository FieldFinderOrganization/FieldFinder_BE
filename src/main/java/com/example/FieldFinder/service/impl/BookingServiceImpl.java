package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.CancelActor;
import com.example.FieldFinder.Enum.DiscountKind;
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
import com.example.FieldFinder.service.DiscountUsageService;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.NotificationService;
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
    private final DiscountRepository discountRepository;
    private final DiscountUsageService discountUsageService;
    private final UserDiscountRepository userDiscountRepository;
    private final NotificationService notificationService;

    /** Khoảng thời gian tối thiểu trước slot đầu mới được hủy + hoàn tiền. */
    private static final long BOOKING_REFUND_MIN_MINUTES_BEFORE = 60;

    /** Provider hủy sát giờ hơn ngưỡng này → đền bù thêm cho khách. */
    private static final long PROVIDER_LATE_CANCEL_THRESHOLD_MINUTES = 60;

    /** Tỷ lệ hoàn khi provider hủy sát giờ (110%). */
    private static final BigDecimal PROVIDER_LATE_CANCEL_RATE = new BigDecimal("1.10");

    /** Đơn PENDING (online chưa thanh toán) giữ slot quá hạn này → tự hủy, thả slot cho người khác. */
    private static final long PENDING_HOLD_TIMEOUT_MINUTES = 15;

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
            BigDecimal subtotal = bookingRequest.getBookingDetails().stream()
                    .map(BookingRequestDTO.BookingDetailDTO::getPriceDetail)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal finalPrice = subtotal;
            List<UserDiscount> consumedDiscounts = new ArrayList<>();
            List<BigDecimal> consumedAmounts = new ArrayList<>(); // song song consumedDiscounts
            List<String> codes = bookingRequest.getDiscountCodes();
            if (codes != null && !codes.isEmpty()) {
                for (String code : codes) {
                    if (code == null || code.isBlank()) continue;
                    Discount d = discountRepository.findByCode(code)
                            .orElseThrow(() -> new RuntimeException("Mã không tồn tại: " + code));
                    if (d.getStatus() != Discount.DiscountStatus.ACTIVE) {
                        throw new RuntimeException("Mã không còn hiệu lực: " + code);
                    }
                    LocalDate today = LocalDate.now();
                    if (today.isBefore(d.getStartDate()) || today.isAfter(d.getEndDate())) {
                        throw new RuntimeException("Mã ngoài thời hạn: " + code);
                    }
                    boolean isRefund = d.getKind() == DiscountKind.REFUND_CREDIT;
                    boolean isPromoGlobal = d.getKind() == DiscountKind.PROMOTION
                            && d.getScope() == Discount.DiscountScope.GLOBAL;
                    if (!isRefund && !isPromoGlobal) {
                        throw new RuntimeException("Mã không áp dụng cho đặt sân: " + code);
                    }
                    // Mã hoàn do provider hủy phát hành: chỉ dùng cho sân của provider đó
                    if (isRefund && d.getRestrictProviderId() != null) {
                        UUID pitchProviderId = (pitch.getProviderAddress() != null
                                && pitch.getProviderAddress().getProvider() != null)
                                ? pitch.getProviderAddress().getProvider().getProviderId()
                                : null;
                        if (!d.getRestrictProviderId().equals(pitchProviderId)) {
                            throw new RuntimeException(
                                    "Mã này chỉ dùng được cho sân của chủ sân đã phát hành: " + code);
                        }
                    }
                    UserDiscount ud = userDiscountRepository.findByUserAndDiscount(user, d)
                            .orElseThrow(() -> new RuntimeException("Bạn chưa sở hữu mã: " + code));
                    if (ud.isUsed()) {
                        throw new RuntimeException("Mã đã sử dụng: " + code);
                    }
                    if (d.getMinTier() != null
                            && !user.getEffectiveTier().isAtLeast(d.getMinTier())) {
                        throw new RuntimeException("Mã chỉ dành cho hạng "
                                + d.getMinTier().name() + " trở lên: " + code);
                    }

                    BigDecimal discountAmount;
                    if (isRefund) {
                        BigDecimal remaining = ud.getRemainingValue() != null
                                ? ud.getRemainingValue() : d.getValue();
                        if (remaining.signum() <= 0) {
                            throw new RuntimeException("Mã hết số dư: " + code);
                        }
                        discountAmount = remaining.min(finalPrice);
                        ud.setRemainingValue(remaining.subtract(discountAmount));
                        if (ud.getRemainingValue().signum() <= 0) {
                            ud.setUsed(true);
                            ud.setUsedAt(LocalDateTime.now());
                        }
                    } else {
                        if (d.getDiscountType() == Discount.DiscountType.PERCENTAGE) {
                            discountAmount = finalPrice.multiply(d.getValue())
                                    .divide(BigDecimal.valueOf(100));
                            if (d.getMaxDiscountAmount() != null
                                    && discountAmount.compareTo(d.getMaxDiscountAmount()) > 0) {
                                discountAmount = d.getMaxDiscountAmount();
                            }
                        } else {
                            discountAmount = d.getValue();
                        }
                        if (d.getMinOrderValue() != null
                                && finalPrice.compareTo(d.getMinOrderValue()) < 0) {
                            throw new RuntimeException("Đơn chưa đạt giá trị tối thiểu: " + code);
                        }
                        discountAmount = discountAmount.min(finalPrice);
                        // quantity = tổng lượt dùng; trừ atomic tại thời điểm dùng
                        if (discountRepository.decrementQuantity(d.getDiscountId()) == 0) {
                            throw new RuntimeException("Mã đã hết lượt sử dụng: " + code);
                        }
                        ud.setUsed(true);
                        ud.setUsedAt(LocalDateTime.now());
                    }
                    finalPrice = finalPrice.subtract(discountAmount);
                    if (finalPrice.signum() < 0) finalPrice = BigDecimal.ZERO;
                    consumedDiscounts.add(ud);
                    consumedAmounts.add(discountAmount);
                }
                userDiscountRepository.saveAll(consumedDiscounts);
            }

            Booking booking = Booking.builder()
                    .user(user)
                    .bookingDate(bookingRequest.getBookingDate())
                    .createdAt(LocalDateTime.now())
                    .status(BookingStatus.PENDING)
                    .paymentStatus(PaymentStatus.PENDING)
                    .totalPrice(finalPrice)
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

            // Ghi lượt dùng voucher để hoàn lại nếu booking bị hủy
            for (int i = 0; i < consumedDiscounts.size(); i++) {
                discountUsageService.recordForBooking(
                        consumedDiscounts.get(i),
                        savedBooking.getBookingId(),
                        consumedAmounts.get(i));
            }

            if (bookingRequest.getPaymentMethod() != null &&
                    bookingRequest.getPaymentMethod().equalsIgnoreCase("CASH")) {
                Payment payment = Payment.builder()
                        .booking(savedBooking)
                        .user(user)
                        .amount(savedBooking.getTotalPrice())
                        .paymentMethod(PaymentMethod.CASH)
                        .paymentStatus(PaymentStatus.PAID)
                        .createdAt(LocalDateTime.now())
                        .paidAt(LocalDateTime.now())
                        .build();
                paymentRepository.save(payment);
                // CASH: booking CONFIRMED ngay lúc tạo (dòng set status phía trên)
                notificationService.notifyBookingConfirmed(savedBooking);
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

            List<String> slotsName = booking.getBookingDetails() == null ? new ArrayList<>()
                    : booking.getBookingDetails().stream()
                      .map(BookingDetail::getName)
                      .filter(Objects::nonNull)
                      .collect(Collectors.toList());

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
                    .slotsName(slotsName)
                    .cancelledBy(booking.getCancelledBy() != null ? booking.getCancelledBy().name() : null)
                    .cancelReason(booking.getCancelReason())
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

        // Admin hủy tay cũng phải hoàn voucher như các đường hủy khác
        if (newStatus == BookingStatus.CANCELED) {
            discountUsageService.revertForBooking(booking.getBookingId());
        }

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

            List<String> slotsName = booking.getBookingDetails() == null ? new ArrayList<>()
                    : booking.getBookingDetails().stream()
                      .map(BookingDetail::getName)
                      .filter(Objects::nonNull)
                      .collect(Collectors.toList());

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
                    .slotsName(slotsName)
                    .createdAt(booking.getCreatedAt())
                    .paidAt(paidAt)
                    .cancelledBy(booking.getCancelledBy() != null ? booking.getCancelledBy().name() : null)
                    .cancelReason(booking.getCancelReason())
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
    public void cancelBookingByUser(UUID bookingId, UUID userId, String reason) {
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
        cancelBooking(booking, CancelActor.USER,
                reason != null && !reason.isBlank() ? reason : "User requested cancellation");

        // Chỉ phát hành mã hoàn tiền cho booking thanh toán BANK (đã trả tiền thật).
        // CASH (trả tại sân) hủy là hủy, không tạo mã khuyến mãi.
        if (isPaidConfirmed && booking.getTotalPrice() != null
                && booking.getTotalPrice().signum() > 0) {
            Payment latestPayment = paymentRepository
                    .findFirstByBooking_BookingIdOrderByCreatedAtDesc(booking.getBookingId())
                    .orElse(null);
            boolean isBank = latestPayment != null
                    && latestPayment.getPaymentMethod() == PaymentMethod.BANK;

            if (isBank) {
                refundService.issueRefundCredit(
                        booking.getUser(),
                        RefundSourceType.BOOKING,
                        booking.getBookingId().toString(),
                        booking.getTotalPrice(),
                        reason != null && !reason.isBlank()
                                ? reason
                                : "User cancel booking ≥60m before start");

                latestPayment.setPaymentStatus(PaymentStatus.REFUNDED);
                latestPayment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(latestPayment);
                booking.setPaymentStatus(PaymentStatus.REFUNDED);
                bookingRepository.save(booking);
            }
        }

        // Unlock Redis slots only after the transaction commits successfully
        unlockSlotsAfterCommit(booking);
    }

    /** Thả khóa Redis của mọi slot trong booking SAU khi transaction commit. */
    private void unlockSlotsAfterCommit(Booking booking) {
        final String userIdStr = booking.getUser().getUserId().toString();
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
    @Transactional
    public void cancelBookingByProvider(UUID bookingId, UUID providerUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new RuntimeException("Phải nêu lý do khi hủy đơn đặt sân của khách!");
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found!"));

        if (booking.getStatus() == BookingStatus.CANCELED) {
            throw new RuntimeException("Đơn đặt sân đã bị hủy trước đó!");
        }

        Provider provider = providerRepository.findByUser_UserId(providerUserId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy thông tin chủ sân!"));

        // Ownership: mọi sân trong đơn phải thuộc provider này
        boolean ownsAll = booking.getBookingDetails() != null
                && !booking.getBookingDetails().isEmpty()
                && booking.getBookingDetails().stream().allMatch(detail -> {
                    Pitch p = detail.getPitch();
                    return p != null
                            && p.getProviderAddress() != null
                            && p.getProviderAddress().getProvider() != null
                            && provider.getProviderId().equals(
                                    p.getProviderAddress().getProvider().getProviderId());
                });
        if (!ownsAll) {
            throw new RuntimeException("Bạn không có quyền hủy đơn đặt sân này!");
        }

        // Chỉ hủy được trước giờ slot đầu tiên
        LocalDateTime earliestStart = earliestSlotStart(booking);
        if (earliestStart == null) {
            throw new RuntimeException("Không xác định được giờ bắt đầu của slot!");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(earliestStart)) {
            throw new RuntimeException("Đơn đã/đang diễn ra, không thể hủy!");
        }

        // Xác định nhánh hoàn theo payment mới nhất
        boolean isPaidConfirmed = booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getPaymentStatus() == PaymentStatus.PAID;
        Payment latestPayment = paymentRepository
                .findFirstByBooking_BookingIdOrderByCreatedAtDesc(booking.getBookingId())
                .orElse(null);
        boolean isBank = latestPayment != null
                && latestPayment.getPaymentMethod() == PaymentMethod.BANK;
        boolean isCash = latestPayment != null
                && latestPayment.getPaymentMethod() == PaymentMethod.CASH;

        cancelBooking(booking, CancelActor.PROVIDER, reason);

        if (isPaidConfirmed && isBank
                && booking.getTotalPrice() != null && booking.getTotalPrice().signum() > 0) {
            // Đền bù 110% khi hủy sát giờ (<60p) NHƯNG đơn phải được tạo trước slot
            // ≥60p — đơn đặt sát giờ rồi bị hủy ngay chỉ hoàn 100% (chặn farm credit).
            long minutesUntil = ChronoUnit.MINUTES.between(now, earliestStart);
            long bookingAgeBeforeSlot = ChronoUnit.MINUTES.between(
                    booking.getCreatedAt() != null ? booking.getCreatedAt() : now, earliestStart);
            BigDecimal multiplier =
                    (minutesUntil < PROVIDER_LATE_CANCEL_THRESHOLD_MINUTES
                            && bookingAgeBeforeSlot >= PROVIDER_LATE_CANCEL_THRESHOLD_MINUTES)
                            ? PROVIDER_LATE_CANCEL_RATE
                            : BigDecimal.ONE;
            BigDecimal refundAmount = booking.getTotalPrice().multiply(multiplier)
                    .setScale(0, java.math.RoundingMode.HALF_UP);

            refundService.issueRefundCredit(
                    booking.getUser(),
                    RefundSourceType.BOOKING,
                    booking.getBookingId().toString(),
                    refundAmount,
                    reason,
                    provider.getProviderId());

            latestPayment.setPaymentStatus(PaymentStatus.REFUNDED);
            latestPayment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(latestPayment);
            booking.setPaymentStatus(PaymentStatus.REFUNDED);
            bookingRepository.save(booking);
        } else if (isCash) {
            // CASH được đánh dấu PAID "lạc quan" từ lúc tạo; khách chưa trả tiền thật
            // (trả tại sân) → không hoàn mã, chỉ dọn trạng thái thanh toán.
            latestPayment.setPaymentStatus(PaymentStatus.CANCELED);
            latestPayment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(latestPayment);
            booking.setPaymentStatus(PaymentStatus.CANCELED);
            bookingRepository.save(booking);
        }

        unlockSlotsAfterCommit(booking);
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

            // 1b. Payment hold timeout: đơn chưa trả quá 15p từ lúc tạo → thả slot ngay,
            // không chờ tới T-5 (lúc đó đã qua mốc đặt 30p, không ai rebook được).
            if (booking.getCreatedAt() != null
                    && ChronoUnit.MINUTES.between(booking.getCreatedAt(), LocalDateTime.now())
                            >= PENDING_HOLD_TIMEOUT_MINUTES) {
                unlockRedisSlots(booking);
                cancelBooking(booking, "Auto-Cancel: Unpaid hold timeout (15m since creation)");
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

    /** Nhắc trước giờ đá: booking CONFIRMED hôm nay, slot đầu bắt đầu trong vòng 60 phút. */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void sendPlayTimeReminders() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<Booking> confirmedToday =
                bookingRepository.findAllByStatusAndDateWithDetails(BookingStatus.CONFIRMED, today);

        for (Booking booking : confirmedToday) {
            if (Boolean.TRUE.equals(booking.getIsPlayReminderSent())) continue;
            if (booking.getUser() == null) continue;

            LocalDateTime start = earliestSlotStart(booking);
            if (start == null) continue;

            long minutesUntilStart = ChronoUnit.MINUTES.between(now, start);
            if (minutesUntilStart > 0 && minutesUntilStart <= 60) {
                String pitchName = booking.getBookingDetails().stream()
                        .map(bd -> bd.getPitch() != null ? bd.getPitch().getName() : null)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("sân");
                notificationService.notify(booking.getUser().getUserId(),
                        "BOOKING_PLAY_REMINDER",
                        "Sắp đến giờ đá!",
                        "Lịch đặt " + pitchName + " bắt đầu lúc "
                                + start.toLocalTime() + " (còn " + minutesUntilStart + " phút).",
                        "BOOKING", booking.getBookingId().toString());
                booking.setIsPlayReminderSent(true);
                bookingRepository.save(booking);
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
        cancelBooking(booking, CancelActor.SYSTEM, reason);
    }

    private void cancelBooking(Booking booking, CancelActor actor, String reason) {
        System.out.println("🚫 " + reason + " for Booking #" + booking.getBookingId());
        booking.setStatus(BookingStatus.CANCELED);
        booking.setCancelledBy(actor);
        booking.setCancelReason(reason);
        bookingRepository.save(booking);

        // Hoàn voucher đã dùng cho booking này (mọi đường hủy đều qua đây)
        discountUsageService.revertForBooking(booking.getBookingId());

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