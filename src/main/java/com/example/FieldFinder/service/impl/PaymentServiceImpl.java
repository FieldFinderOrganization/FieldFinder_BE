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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    @Override
    public PaymentResponseDTO createPaymentQRCode(PaymentRequestDTO requestDTO) {
        Booking booking = bookingRepository.findById(requestDTO.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // Lấy bookingDetail đầu tiên
        BookingDetail bookingDetail = booking.getBookingDetails().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("BookingDetail not found for this booking"));

        // Truy xuất provider qua chuỗi: bookingDetail -> pitch -> providerAddress -> provider
        Provider provider = bookingDetail.getPitch()
                .getProviderAddress()
                .getProvider();

        if (provider == null) {
            throw new RuntimeException("Provider not found");
        }
        if (provider.getBank() == null || provider.getCardNumber() == null) {
            throw new RuntimeException("Provider bank info is missing");
        }

        String bankName = provider.getBank();
        String bankAccountNumber = provider.getCardNumber();
        User providerUser = userRepository.findById(provider.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found for provider"));
        String bankAccountName = providerUser.getName() != null ? providerUser.getName() : "SAN BONG";

        // Lấy bankBin từ bankName bằng BankBinMapper
        String bankBin = BankBinMapper.getBankBin(bankName);
        if (bankBin == null) {
            throw new RuntimeException("Không tìm thấy mã bankBin cho ngân hàng: " + bankName);
        }

        String transactionId = UUID.randomUUID().toString();

        // Chuyển paymentMethod String sang Enum, xử lý nếu không hợp lệ
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
                .transactionId(transactionId)
                .build();

        paymentRepository.save(payment);

        // Tạo QR code URL sử dụng bankBin thay vì bankShortName
        String qrCodeUrl = String.format(
                "https://img.vietqr.io/image/%s-%s-qr_only.png?amount=%s&addInfo=%s",
                bankBin,
                bankAccountNumber,
                requestDTO.getAmount().toString(),
                "Booking_" + transactionId
        );

        return PaymentResponseDTO.builder()
                .qrCodeUrl(qrCodeUrl)
                .amount(requestDTO.getAmount().toString())
                .bankAccountName(bankAccountName)
                .bankAccountNumber(bankAccountNumber)
                .bankName(bankName)
                .transactionId(transactionId)
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
                .amount(payment.getAmount().toString())
                .bankAccountName(bankAccountName)
                .bankAccountNumber(provider.getCardNumber())
                .bankName(provider.getBank())
                .qrCodeUrl(generateQrCodeUrl(payment))
                .paymentMethod(payment.getPaymentMethod().name())
                .build();
    }


    private String generateQrCodeUrl(Payment payment) {
        String bankBin = BankBinMapper.getBankBin(payment.getBooking()
                .getBookingDetails()
                .stream()
                .findFirst()
                .map(detail -> detail.getPitch()
                        .getProviderAddress()
                        .getProvider()
                        .getBank())
                .orElse(""));

        if (bankBin == null) bankBin = "default";

        String bankAccountNumber = payment.getBooking()
                .getBookingDetails()
                .stream()
                .findFirst()
                .map(detail -> detail.getPitch()
                        .getProviderAddress()
                        .getProvider()
                        .getCardNumber())
                .orElse("");

        return String.format(
                "https://img.vietqr.io/image/%s-%s-qr_only.png?amount=%s&addInfo=%s",
                bankBin,
                bankAccountNumber,
                payment.getAmount().toString(),
                "Booking_" + payment.getTransactionId()
        );
    }
}
