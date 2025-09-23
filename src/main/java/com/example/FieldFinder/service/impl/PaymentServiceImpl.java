package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.mapper.BankBinMapper;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    private final UserRepository userRepository;
    private final PayOSService payOSService;

    @Override
    public PaymentResponseDTO createPaymentQRCode(PaymentRequestDTO requestDTO) {
        Booking booking = bookingRepository.findById(requestDTO.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        BookingDetail bookingDetail = booking.getBookingDetails().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("BookingDetail not found"));

        Provider provider = bookingDetail.getPitch()
                .getProviderAddress()
                .getProvider();

        if (provider == null || provider.getBank() == null || provider.getCardNumber() == null)
            throw new RuntimeException("Provider or bank info missing");

        String bankName = provider.getBank();
        String bankAccountNumber = provider.getCardNumber();
        User providerUser = userRepository.findById(provider.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found for provider"));
        String bankAccountName = providerUser.getName() != null ? providerUser.getName() : "SAN BONG";

        String bankBin = BankBinMapper.getBankBin(bankName);
        if (bankBin == null)
            throw new RuntimeException("Không tìm thấy mã bankBin cho ngân hàng: " + bankName);

        // Tạo orderCode
        int orderCode = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);

        // Gọi PayOS để tạo payment
        PayOSService.PaymentResult result = payOSService.createPayment(
                requestDTO.getAmount(),
                orderCode,
                "Thanh toán đặt sân"
        );

        // Lưu payment với transactionId từ PayOS
        Payment.PaymentMethod paymentMethod;
        try {
            paymentMethod = Payment.PaymentMethod.valueOf(requestDTO.getPaymentMethod());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid payment method: " + requestDTO.getPaymentMethod());
        }

        Payment payment = Payment.builder()
                .booking(booking)
                .user(booking.getUser())
                .amount(requestDTO.getAmount())
                .paymentMethod(paymentMethod)
                .paymentStatus(Booking.PaymentStatus.PENDING)
                .checkoutUrl(result.checkoutUrl())
                .transactionId(result.paymentLinkId()) // ✅ Lưu đúng ID từ PayOS
                .build();

        paymentRepository.save(payment);

        return PaymentResponseDTO.builder()
                .transactionId(result.paymentLinkId())
                .checkoutUrl(result.checkoutUrl())
                .amount((requestDTO.getAmount()).toString())
                .status("PENDING")
                .build();
    }


    @Override
    public List<PaymentResponseDTO> getPaymentsByUserId(UUID userId) {
        // Optionally check if user exists, else throw exception
        userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Payment> payments = paymentRepository.findByUser_UserId(userId);
        return payments.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentResponseDTO> getAllPayments() {
        List<Payment> payments = paymentRepository.findAll();
        return payments.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }


    private PaymentResponseDTO convertToDTO(Payment payment) {
        Booking booking = payment.getBooking();

        // Get the first booking detail
        BookingDetail bookingDetail = booking.getBookingDetails().stream()
                .findFirst()
                .orElse(null);

        if (bookingDetail == null) {
            throw new RuntimeException("BookingDetail not found for this booking");
        }

        Provider provider = bookingDetail.getPitch()
                .getProviderAddress()
                .getProvider();

        if (provider == null) {
            throw new RuntimeException("Provider not found");
        }

        // Fetch User manually by provider's userId
        UUID providerUserId = provider.getUserId();
        User providerUser = userRepository.findById(providerUserId)
                .orElseThrow(() -> new RuntimeException("User not found for provider"));

        String bankAccountName = providerUser.getName() != null ? providerUser.getName() : "SAN BONG";

        return PaymentResponseDTO.builder()
                .transactionId(payment.getTransactionId())
                .checkoutUrl(payment.getCheckoutUrl())
                .amount(payment.getAmount().toPlainString()) // đảm bảo không có format lạ như '1E+3'
                .status(payment.getPaymentStatus().name()) // ví dụ: PENDING, PAID, REFUNDED
                .build();

    }




    public void processWebhook(Map<String, Object> payload) {
        String transactionId = (String) payload.get("transactionId");
        String status = (String) payload.get("status");

        if (transactionId == null || status == null) {
            System.out.println("Webhook missing required fields");
            return;
        }

        Optional<Payment> optionalPayment = paymentRepository.findByTransactionId(transactionId);

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            Booking booking = payment.getBooking();

            switch (status.toLowerCase()) {
                case "success":
                    payment.setPaymentStatus(Booking.PaymentStatus.PAID);

                    // ✅ Đặt booking status thành CONFIRMED khi đã thanh toán
                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PAID);
                        booking.setStatus(Booking.BookingStatus.CONFIRMED);
                    }

                    break;
                case "fail":
                    payment.setPaymentStatus(Booking.PaymentStatus.PENDING);
                    if (booking != null) {
                        booking.setPaymentStatus(Booking.PaymentStatus.PENDING);
                    }
                    break;
                default:
                    System.out.println("Unknown status: " + status);
                    return;
            }

            paymentRepository.save(payment); // Cascade sẽ cập nhật booking nếu liên kết đúng
            System.out.println("Payment and Booking updated with status: " + status);
        } else {
            System.out.println("Payment not found for transactionId: " + transactionId);
        }
    }
}
