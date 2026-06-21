package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.config.RabbitMQConfig;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.req.ShopPaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.PaymentService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.UserTierService;
import com.example.FieldFinder.service.strategy.payment.PaymentContext;
import com.example.FieldFinder.service.strategy.payment.PaymentExecutionResult;
import com.example.FieldFinder.service.strategy.payment.PaymentStrategyFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PayOSService payOSService;
    private final ProductService productService;
    private final RabbitTemplate rabbitTemplate;
    private final PaymentStrategyFactory paymentStrategyFactory;
    private final UserTierService userTierService;
    private final com.example.FieldFinder.service.DiscountUsageService discountUsageService;
    private final com.example.FieldFinder.service.NotificationService notificationService;
    private final BookingDetailRepository bookingDetailRepository;
    private final com.example.FieldFinder.service.RefundService refundService;
    private final com.example.FieldFinder.service.BankAccountService bankAccountService;
    private final com.example.FieldFinder.service.PitchRedisLockService pitchRedisLockService;

    @Value("${front_end_url}")
    private String frontEndUrl;

    @Override
    @Transactional
    public PaymentResponseDTO createPaymentQRCode(PaymentRequestDTO requestDTO) {
        Booking booking = bookingRepository.findById(requestDTO.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found!"));

        BookingDetail bookingDetail = booking.getBookingDetails().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("BookingDetail not found!"));

        Provider provider = bookingDetail.getPitch().getProviderAddress().getProvider();

        if (provider == null || provider.getUser() == null)
            throw new RuntimeException("Provider info missing!");

        // TK nhận tiền của chủ sân lấy từ Tài khoản ngân hàng đã đăng ký (nguồn DUY NHẤT, đã verify).
        BankAccount providerBank = bankAccountService.getDefault(provider.getUser().getUserId())
                .orElseThrow(() -> new RuntimeException("Chủ sân chưa đăng ký tài khoản ngân hàng nhận tiền!"));

        int orderCode = generateOrderCode();

        PayOSService.PaymentResult result = payOSService.createPayment(
                requestDTO.getAmount(),
                orderCode,
                "Thanh toan san",
                frontEndUrl + "/payment-success",
                frontEndUrl + "/payment-cancel");

        PaymentMethod paymentMethod = parsePaymentMethod(requestDTO.getPaymentMethod());

        String ownerDisplayName = (providerBank.getAccountName() != null && !providerBank.getAccountName().isBlank())
                ? providerBank.getAccountName()
                : (provider.getUser().getName() != null ? provider.getUser().getName() : "Chủ sân");

        Payment payment = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(requestDTO.getAmount())
                .paymentMethod(paymentMethod)
                .paymentStatus(PaymentStatus.PENDING)
                .checkoutUrl(result.checkoutUrl())
                .transactionId(result.paymentLinkId())
                .qrCode(result.qrCode())
                .ownerName(ownerDisplayName)
                .ownerCardNumber(providerBank.getAccountNumber())
                .ownerBank(providerBank.getBankName())
                .createdAt(LocalDateTime.now())
                .build();


        paymentRepository.save(payment);
        PaymentResponseDTO responseDTO = convertToDTO(payment);
        responseDTO.setQrCode(result.qrCode());
        responseDTO.setOwnerName(ownerDisplayName);
        responseDTO.setOwnerCardNumber(providerBank.getAccountNumber());
        responseDTO.setOwnerBank(providerBank.getBankName());

        return responseDTO;
    }

    @Override
    @Transactional
    public PaymentResponseDTO createShopPayment(ShopPaymentRequestDTO requestDTO) {
        User user = userRepository.findById(requestDTO.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found!"));

        Long orderId = requestDTO.getOrderCode();
        if (orderId == null) {
            throw new RuntimeException("Order ID is required for payment creation");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        String returnUrl = frontEndUrl + "/payment-success?myOrderId=" + order.getOrderId();
        String cancelUrl = frontEndUrl + "/payment-cancel?myOrderId=" + order.getOrderId();

        PaymentMethod paymentMethod = parsePaymentMethod(requestDTO.getPaymentMethod());

        PaymentContext context = new PaymentContext(
                requestDTO.getAmount(),
                order.getOrderId(),
                "Thanh toan don #" + order.getOrderId(),
                returnUrl,
                cancelUrl);

        PaymentExecutionResult result = paymentStrategyFactory.get(paymentMethod).execute(context);

        Payment payment = Payment.builder()
                .order(order)
                .user(user)
                .amount(requestDTO.getAmount())
                .paymentMethod(paymentMethod)
                .paymentStatus(PaymentStatus.PENDING)
                .checkoutUrl(result.checkoutUrl())
                .transactionId(result.transactionId())
                .qrCode(result.qrCode())
                .build();

        paymentRepository.save(payment);

        return PaymentResponseDTO.builder()
                .transactionId(result.transactionId())
                .checkoutUrl(result.checkoutUrl())
                .qrCode(result.qrCode())
                .amount(requestDTO.getAmount().toString())
                .status("PENDING")
                .build();
    }

    @Override
    public List<PaymentResponseDTO> getPaymentsByUserId(UUID userId) {
        userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found!"));
        List<Payment> payments = paymentRepository.findByUser_UserId(userId);
        return payments.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<PaymentResponseDTO> getAllPayments() {
        List<Payment> payments = paymentRepository.findAll();
        return payments.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    private PaymentResponseDTO convertToDTO(Payment payment) {
        return PaymentResponseDTO.builder()
                .transactionId(payment.getTransactionId())
                .checkoutUrl(payment.getCheckoutUrl())
                .amount(payment.getAmount().toPlainString())
                .status(payment.getPaymentStatus().name())
                .qrCode(payment.getQrCode())
                .ownerName(payment.getOwnerName())
                .ownerCardNumber(payment.getOwnerCardNumber())
                .ownerBank(payment.getOwnerBank())
                .build();
    }

    private int generateOrderCode() {
        int code = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        return code < 0 ? -code : code;
    }

    private PaymentMethod parsePaymentMethod(String method) {
        try {
            if (method == null)
                return PaymentMethod.CASH;
            return PaymentMethod.valueOf(method.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid payment method: " + method + ". Allowed: BANK, CASH");
        }
    }

    @Override
    @Transactional
    public void processWebhook(Map<String, Object> payload) {
        System.out.println("🔔 WEBHOOK RECEIVED: " + payload);

        String code = (String) payload.get("code");
        String desc = (String) payload.get("desc");

        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        String transactionId = null;

        if (data != null) {
            Object linkIdObj = data.get("paymentLinkId");
            if (linkIdObj != null)
                transactionId = String.valueOf(linkIdObj);
        }

        if (transactionId == null || code == null) {
            System.out.println("❌ Invalid Webhook Payload: Missing paymentLinkId (in data) or code");
            return;
        }

        Optional<Payment> optionalPayment = paymentRepository.findByTransactionId(transactionId);

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            boolean isAlreadyPaid = payment.getPaymentStatus() == PaymentStatus.PAID;

            Booking booking = payment.getBooking();
            Order order = payment.getOrder();

            boolean isSuccess = "00".equals(code) || "success".equalsIgnoreCase(desc);

            if (isSuccess) {
                payment.setProcessedAt(LocalDateTime.now());
                if (!isAlreadyPaid) {
                    System.out.println("✅ Payment Success for TxID: " + transactionId);
                    payment.setPaymentStatus(PaymentStatus.PAID);

                    LocalDateTime paidTime = LocalDateTime.now();
                    if (data != null && data.containsKey("transactionDateTime")) {
                        String transTimeStr = (String) data.get("transactionDateTime");
                        try {
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                            paidTime = LocalDateTime.parse(transTimeStr, formatter);
                        } catch (Exception e) {
                            System.err.println("Lỗi parse ngày tháng từ webhook: " + e.getMessage());
                        }
                    }
                    payment.setPaidAt(paidTime);

                    if (order != null) {
                        order.setPaymentTime(paidTime);
                    }

                    // A. Update Booking
                    if (booking != null) {
                        if (booking.getStatus() == BookingStatus.CANCELED) {
                            // Giao dịch trễ (Transaction Lag): đơn đã tự hủy do hết hạn giữ slot
                            // TRƯỚC khi tiền về. Khôi phục nếu slot còn trống, ngược lại hoàn 100%.
                            handleLateBookingWebhook(booking, payment, paidTime);
                        } else {
                            booking.setPaymentStatus(PaymentStatus.PAID);
                            booking.setStatus(BookingStatus.CONFIRMED);

                            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE,
                                    RabbitMQConfig.BOOKING_EMAIL_ROUTING_KEY, booking.getBookingId().toString());
                            notificationService.notifyBookingConfirmed(booking);
                        }
                    }


                    if (order != null) {
                        if (order.getStatus() == OrderStatus.PENDING) {
                            order.setStatus(OrderStatus.CONFIRMED); // Or PAID
                            if (order.getItems() != null) {
                                for (OrderItem item : order.getItems()) {
                                    System.out.println("   - Committing stock for Product: "
                                            + item.getProduct().getName() + ", Size: " + item.getSize());
                                    productService.commitStock(
                                            item.getProduct().getProductId(),
                                            item.getSize(),
                                            item.getQuantity());
                                }
                            }

                            System.out.println("📧 Sending confirmation email for Order #" + order.getOrderId());
                            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE,
                                    RabbitMQConfig.ORDER_EMAIL_ROUTING_KEY, order.getOrderId().toString());

                            if (order.getUser() != null) {
                                userTierService.recalcTier(order.getUser().getUserId());
                                notificationService.notify(order.getUser().getUserId(),
                                        "ORDER_CONFIRMED",
                                        "Đơn hàng #" + order.getOrderId() + " đã xác nhận",
                                        "Thanh toán thành công, đơn hàng đang được chuẩn bị.",
                                        "ORDER", String.valueOf(order.getOrderId()));
                            }
                        }
                    }
                }
            } else {
                System.out.println("❌ Payment Failed/Cancelled for TxID: " + transactionId);

                if (!isAlreadyPaid) {
                    payment.setPaymentStatus(PaymentStatus.PENDING);

                    if (booking != null) {
                        booking.setPaymentStatus(PaymentStatus.PENDING);
                    }

                    if (order != null && order.getStatus() == OrderStatus.PENDING) {
                        order.setStatus(OrderStatus.CANCELED);

                        // Thanh toán fail → trả voucher về ví user
                        discountUsageService.revertForOrder(order.getOrderId());

                        if (order.getItems() != null) {
                            for (OrderItem item : order.getItems()) {
                                System.out.println("   - Releasing stock for Product: " + item.getProduct().getName()
                                        + ", Size: " + item.getSize());
                                productService.releaseStock(
                                        item.getProduct().getProductId(),
                                        item.getSize(),
                                        item.getQuantity());
                            }
                        }
                    }
                }
            }

            paymentRepository.save(payment);
            if (order != null)
                orderRepository.save(order);
            if (booking != null)
                bookingRepository.save(booking);

            System.out.println("💾 Saved updated Payment/Order/Booking to DB.");
        } else {
            System.out.println("❌ Payment not found in DB for transactionId: " + transactionId);
        }
    }

    /**
     * Xử lý webhook báo thanh toán THÀNH CÔNG cho một booking đã bị tự hủy
     * (CANCELED do hết hạn giữ slot) — tiền về trễ hơn lúc auto-cancel.
     * - Slot còn trống  → khôi phục CONFIRMED + PAID, khóa lại slot, báo thành công.
     * - Slot đã có người → hoàn 100% (tiền mặt về TK mặc định, fallback voucher) + xin lỗi.
     */
    private void handleLateBookingWebhook(Booking booking, Payment payment, LocalDateTime paidTime) {
        User user = booking.getUser();
        String userIdStr = user.getUserId().toString();
        LocalDate date = booking.getBookingDate();

        // Gom slot của booking này theo từng sân
        Map<UUID, List<Integer>> slotsByPitch = new HashMap<>();
        for (BookingDetail d : booking.getBookingDetails()) {
            if (d.getPitch() == null || d.getTimeSlot() == null) continue;
            slotsByPitch.computeIfAbsent(d.getPitch().getPitchId(), k -> new ArrayList<>())
                    .add(d.getTimeSlot().getSlotId());
        }

        // Slot đã bị booking khác (non-canceled) chiếm chưa? (DB là nguồn chính xác)
        boolean slotTaken = false;
        for (Map.Entry<UUID, List<Integer>> e : slotsByPitch.entrySet()) {
            List<BookingDetail> active = bookingDetailRepository.findByPitchAndDateExcludingStatuses(
                    e.getKey(), date, List.of(BookingStatus.CANCELED));
            for (BookingDetail bd : active) {
                if (bd.getBooking() == null
                        || bd.getBooking().getBookingId().equals(booking.getBookingId())) continue;
                if (bd.getTimeSlot() != null && e.getValue().contains(bd.getTimeSlot().getSlotId())) {
                    slotTaken = true;
                    break;
                }
            }
            if (slotTaken) break;
        }

        // Slot trống → thử khóa lại Redis (chặn create đang chạy). Thua khóa = coi như đã bị giữ.
        if (!slotTaken) {
            List<UUID> lockedPitches = new ArrayList<>();
            for (Map.Entry<UUID, List<Integer>> e : slotsByPitch.entrySet()) {
                if (pitchRedisLockService.lockSlots(e.getKey(), date, e.getValue(), userIdStr)) {
                    lockedPitches.add(e.getKey());
                } else {
                    slotTaken = true;
                    break;
                }
            }
            if (slotTaken) {
                // Dọn các khóa đã giữ được trước khi rơi xuống nhánh hoàn tiền
                for (UUID pitchId : lockedPitches) {
                    for (Integer slot : slotsByPitch.get(pitchId)) {
                        pitchRedisLockService.unlockSlot(pitchId, date, slot, userIdStr);
                    }
                }
            }
        }

        if (!slotTaken) {
            // Khôi phục đơn
            payment.setPaymentStatus(PaymentStatus.PAID);
            payment.setPaidAt(paidTime);
            booking.setPaymentStatus(PaymentStatus.PAID);
            booking.setStatus(BookingStatus.CONFIRMED);

            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE,
                    RabbitMQConfig.BOOKING_EMAIL_ROUTING_KEY, booking.getBookingId().toString());
            notificationService.notifyBookingConfirmed(booking);
            System.out.println("♻️ Late webhook: khôi phục booking #" + booking.getBookingId()
                    + " (slot còn trống)");
            return;
        }

        // Slot đã có người khác đặt → hoàn 100% số tiền đã trả, đơn giữ nguyên CANCELED
        BigDecimal amount = payment.getAmount() != null ? payment.getAmount() : booking.getTotalPrice();
        String reason = "Hoàn 100%: khung giờ đã có người đặt khi thanh toán về trễ (giao dịch trễ)";
        if (amount != null && amount.signum() > 0) {
            bankAccountService.getDefault(user.getUserId()).ifPresentOrElse(
                    bank -> refundService.issueCashRefund(user, RefundSourceType.BOOKING,
                            booking.getBookingId().toString(), amount, reason, bank),
                    () -> refundService.issueRefundCredit(user, RefundSourceType.BOOKING,
                            booking.getBookingId().toString(), amount, reason));
        }
        payment.setPaymentStatus(PaymentStatus.REFUNDED);
        payment.setPaidAt(paidTime);
        payment.setProcessedAt(LocalDateTime.now());

        notificationService.notify(user.getUserId(), "BOOKING_REFUND",
                "Đã hoàn tiền đặt sân",
                "Rất tiếc, khung giờ bạn chọn đã có người đặt khi thanh toán của bạn về trễ. "
                        + "Chúng tôi đã hoàn 100% số tiền cho bạn.",
                "BOOKING", booking.getBookingId().toString());
        System.out.println("💸 Late webhook: slot đã bị giữ, hoàn 100% cho booking #"
                + booking.getBookingId());
    }

    @Override
    public PaymentResponseDTO getPaymentStatusByBookingId(UUID bookingId) {
        Payment payment = paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId)
                .orElseThrow(() -> new RuntimeException("Payment not found for booking: " + bookingId));
        return convertToDTO(payment);
    }

    @Override
    public PaymentResponseDTO getPaymentStatusByOrderId(Long orderId) {
        Payment payment = paymentRepository.findFirstByOrder_OrderIdOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));
        return convertToDTO(payment);
    }
}