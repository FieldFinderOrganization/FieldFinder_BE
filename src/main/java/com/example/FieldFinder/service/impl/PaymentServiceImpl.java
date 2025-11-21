package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.req.ShopPaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.mapper.BankBinMapper;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.PaymentService;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

        Payment.PaymentMethod paymentMethod = parsePaymentMethod(requestDTO.getPaymentMethod());

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

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING)
                .paymentMethod(PaymentMethod.valueOf(requestDTO.getPaymentMethod()))
                .totalAmount(requestDTO.getAmount().doubleValue())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        order = orderRepository.save(order);

        List<OrderItem> orderItems = new ArrayList<>();
        for (ShopPaymentRequestDTO.CartItemDTO itemDTO : requestDTO.getItems()) {
            Product product = productRepository.findById(itemDTO.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemDTO.getProductId()));

            // Giữ hàng
            productService.holdStock(product.getProductId(), itemDTO.getQuantity());

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(itemDTO.getQuantity())
                    .price(product.getPrice() * itemDTO.getQuantity())
                    .build();

            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);
        order.setItems(orderItems);

        String checkoutUrl = null;
        String transactionId = null;
        String returnUrl = frontEndUrl + "/payment-success";
        String cancelUrl = frontEndUrl + "/payment-cancel";

        if ("BANK".equalsIgnoreCase(requestDTO.getPaymentMethod())) {
            int orderCode = generateOrderCode();

            PayOSService.PaymentResult result = payOSService.createPayment(
                    requestDTO.getAmount(),
                    orderCode,
                    "Thanh toan don hang",
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
                .paymentMethod(Payment.PaymentMethod.valueOf(requestDTO.getPaymentMethod()))
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

    private Payment.PaymentMethod parsePaymentMethod(String method) {
        try {
            return Payment.PaymentMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid payment method: " + method);
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
            transactionId = (String) data.get("paymentLinkId");
        }

        // Nếu không tìm thấy ID hoặc Code, dừng lại
        if (transactionId == null || code == null) {
            System.out.println("Invalid Webhook Payload: Missing paymentLinkId or code");
            return;
        }

        Optional<Payment> optionalPayment = paymentRepository.findByTransactionId(transactionId);

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            boolean isAlreadyPaid = payment.getPaymentStatus() == Booking.PaymentStatus.PAID;

            Booking booking = payment.getBooking();
            Order order = payment.getOrder();

            // 3. Kiểm tra trạng thái (00 là thành công)
            boolean isSuccess = "00".equals(code) || "success".equalsIgnoreCase(desc);

            if (isSuccess) {
                if (!isAlreadyPaid) {
                    System.out.println("✅ Payment Success for TxID: " + transactionId);
                    payment.setPaymentStatus(Booking.PaymentStatus.PAID);

                    // A. Xử lý Đặt Sân
                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PAID);
                        booking.setStatus(Booking.BookingStatus.CONFIRMED);
                    }

                    // B. Xử lý Đơn Hàng -> TRỪ KHO THẬT (COMMIT)
                    if (order != null) {
                        order.setStatus(OrderStatus.CONFIRMED);
                        // Cần kiểm tra null cho items để tránh lỗi
                        if (order.getItems() != null) {
                            for (OrderItem item : order.getItems()) {
                                System.out.println("   - Committing stock for Product: " + item.getProduct().getName());
                                productService.commitStock(item.getProduct().getProductId(), item.getQuantity());
                            }
                        }
                    }
                }
            } else {
                // Thanh toán thất bại / Hủy
                System.out.println("Payment Failed/Cancelled for TxID: " + transactionId);

                if (!isAlreadyPaid) {
                    payment.setPaymentStatus(Booking.PaymentStatus.PENDING); // Hoặc FAILED

                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PENDING);
                    }

                    // C. Xử lý Đơn Hàng -> TRẢ HÀNG (RELEASE)
                    if (order != null) {
                        order.setStatus(OrderStatus.CANCELLED);
                        if (order.getItems() != null) {
                            for (OrderItem item : order.getItems()) {
                                System.out.println("   - Releasing stock for Product: " + item.getProduct().getName());
                                productService.releaseStock(item.getProduct().getProductId(), item.getQuantity());
                            }
                        }
                    }
                }
            }

            paymentRepository.save(payment);
            if (order != null) orderRepository.save(order);
            if (booking != null) bookingRepository.save(booking);

        } else {
            System.out.println("Payment not found in DB for transactionId: " + transactionId);
        }
    }
}