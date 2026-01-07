package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.req.ShopPaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.mapper.BankBinMapper;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.PaymentService;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal; // Import BigDecimal
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PayOSService payOSService;
    private final ProductService productService;
    private final EmailService emailService;

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

        if (provider == null || provider.getBank() == null || provider.getCardNumber() == null)
            throw new RuntimeException("Provider or bank info missing!");

        String bankName = provider.getBank();
        String bankBin = BankBinMapper.getBankBin(bankName);
        if (bankBin == null)
            throw new RuntimeException("Không tìm thấy mã bankBin cho: " + bankName);

        int orderCode = generateOrderCode();

        PayOSService.PaymentResult result = payOSService.createPayment(
                requestDTO.getAmount(),
                orderCode,
                "Thanh toan san",
                frontEndUrl + "/payment-success",
                frontEndUrl + "/payment-cancel"
        );

        PaymentMethod paymentMethod = parsePaymentMethod(requestDTO.getPaymentMethod());

        Payment payment = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(requestDTO.getAmount())
                .paymentMethod(paymentMethod)
                .paymentStatus(Booking.PaymentStatus.PENDING)
                .checkoutUrl(result.checkoutUrl())
                .transactionId(result.paymentLinkId())
                .build();

        paymentRepository.save(payment);
        return convertToDTO(payment);
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

        for (ShopPaymentRequestDTO.CartItemDTO itemDTO : requestDTO.getItems()) {
            Product product = productRepository.findById(itemDTO.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemDTO.getProductId()));

            String size = itemDTO.getSize();
            if (size == null || size.isEmpty()) {
                throw new RuntimeException("Size is required for product: " + product.getName());
            }

            productService.holdStock(product.getProductId(), size, itemDTO.getQuantity());
        }

        String checkoutUrl = null;
        String transactionId = null;

        String returnUrl = frontEndUrl + "/payment-success?myOrderId=" + order.getOrderId();
        String cancelUrl = frontEndUrl + "/payment-cancel?myOrderId=" + order.getOrderId();

        if ("BANK".equalsIgnoreCase(requestDTO.getPaymentMethod())) {
            int payOsOrderCode = generateOrderCode();

            PayOSService.PaymentResult result = payOSService.createPayment(
                    requestDTO.getAmount(),
                    payOsOrderCode,
                    "Thanh toan don #" + order.getOrderId(),
                    returnUrl,
                    cancelUrl
            );
            checkoutUrl = result.checkoutUrl();
            transactionId = result.paymentLinkId();
        } else {
            checkoutUrl = returnUrl;
            transactionId = "COD-" + System.currentTimeMillis();
        }

        Payment payment = Payment.builder()
                .order(order)
                .user(user)
                .amount(requestDTO.getAmount())
                .paymentMethod(parsePaymentMethod(requestDTO.getPaymentMethod()))
                .paymentStatus(Booking.PaymentStatus.PENDING)
                .checkoutUrl(checkoutUrl)
                .transactionId(transactionId)
                .build();

        paymentRepository.save(payment);

        return PaymentResponseDTO.builder()
                .transactionId(transactionId)
                .checkoutUrl(checkoutUrl)
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
                .build();
    }

    private int generateOrderCode() {
        int code = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        return code < 0 ? -code : code;
    }

    private PaymentMethod parsePaymentMethod(String method) {
        try {
            if (method == null) return PaymentMethod.CASH;
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
            if (linkIdObj != null) transactionId = String.valueOf(linkIdObj);
        }

        if (transactionId == null || code == null) {
            System.out.println("❌ Invalid Webhook Payload: Missing paymentLinkId (in data) or code");
            return;
        }

        Optional<Payment> optionalPayment = paymentRepository.findByTransactionId(transactionId);

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            boolean isAlreadyPaid = payment.getPaymentStatus() == Booking.PaymentStatus.PAID;

            Booking booking = payment.getBooking();
            Order order = payment.getOrder();

            boolean isSuccess = "00".equals(code) || "success".equalsIgnoreCase(desc);

            if (isSuccess) {
                if (!isAlreadyPaid) {
                    System.out.println("✅ Payment Success for TxID: " + transactionId);
                    payment.setPaymentStatus(Booking.PaymentStatus.PAID);

                    if (data != null && data.containsKey("transactionDateTime")) {
                        String transTimeStr = (String) data.get("transactionDateTime");
                        try {
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                            LocalDateTime paidAt = LocalDateTime.parse(transTimeStr, formatter);

                            if (order != null) {
                                order.setPaymentTime(paidAt);
                            }
                        } catch (Exception e) {
                            System.err.println("Lỗi parse ngày tháng từ webhook: " + e.getMessage());
                            if (order != null) order.setPaymentTime(LocalDateTime.now());
                        }
                    }

                    // A. Update Booking
                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PAID);
                        booking.setStatus(Booking.BookingStatus.CONFIRMED);
                    }

                    if (order != null) {
                        if (order.getStatus() == OrderStatus.PENDING) {
                            order.setStatus(OrderStatus.CONFIRMED); // Or PAID
                            if (order.getItems() != null) {
                                for (OrderItem item : order.getItems()) {
                                    System.out.println("   - Committing stock for Product: " + item.getProduct().getName() + ", Size: " + item.getSize());
                                    productService.commitStock(
                                            item.getProduct().getProductId(),
                                            item.getSize(),
                                            item.getQuantity()
                                    );
                                }
                            }

                            System.out.println("📧 Sending confirmation email for Order #" + order.getOrderId());
                            emailService.sendOrderConfirmation(order);
                        }
                    }
                }
            } else {
                System.out.println("❌ Payment Failed/Cancelled for TxID: " + transactionId);

                if (!isAlreadyPaid) {
                    payment.setPaymentStatus(Booking.PaymentStatus.PENDING);

                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PENDING);
                    }

                    if (order != null && order.getStatus() == OrderStatus.PENDING) {
                        order.setStatus(OrderStatus.CANCELLED);
                        if (order.getItems() != null) {
                            for (OrderItem item : order.getItems()) {
                                System.out.println("   - Releasing stock for Product: " + item.getProduct().getName() + ", Size: " + item.getSize());
                                productService.releaseStock(
                                        item.getProduct().getProductId(),
                                        item.getSize(),
                                        item.getQuantity()
                                );
                            }
                        }
                    }
                }
            }

            paymentRepository.save(payment);
            if (order != null) orderRepository.save(order);
            if (booking != null) bookingRepository.save(booking);

            System.out.println("💾 Saved updated Payment/Order/Booking to DB.");

        } else {
            System.out.println("❌ Payment not found in DB for transactionId: " + transactionId);
        }
    }
}