package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.dto.req.PaymentRequestDTO;
import com.example.FieldFinder.dto.req.ShopPaymentRequestDTO;
import com.example.FieldFinder.dto.res.PaymentResponseDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock PaymentRepository paymentRepository;
    @Mock BookingRepository bookingRepository;
    @Mock OrderRepository orderRepository;
    @Mock UserRepository userRepository;
    @Mock PayOSService payOSService;
    @Mock RabbitTemplate rabbitTemplate;

    @InjectMocks PaymentServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontEndUrl", "http://localhost:3000");
        userId = UUID.randomUUID();
        user = new User();
        user.setUserId(userId);
    }

    @Nested
    class createPaymentQRCode {
        @Test
        void valid_returnsResponseDTO() {
            UUID bookingId = UUID.randomUUID();
            Provider provider = new Provider();
            provider.setBank("VCB");
            provider.setCardNumber("1234567890");
            User providerUser = new User();
            providerUser.setName("Owner");
            provider.setUser(providerUser);

            ProviderAddress addr = new ProviderAddress();
            addr.setProvider(provider);
            Pitch pitch = new Pitch();
            pitch.setProviderAddress(addr);
            BookingDetail bd = new BookingDetail();
            bd.setPitch(pitch);

            Booking booking = Booking.builder()
                    .bookingId(bookingId)
                    .user(user)
                    .bookingDetails(List.of(bd))
                    .build();
            bd.setBooking(booking);

            when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
            when(payOSService.createPayment(any(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(new PayOSService.PaymentResult("url", "linkId", "qr"));

            PaymentRequestDTO req = new PaymentRequestDTO();
            req.setBookingId(bookingId);
            req.setUserId(userId);
            req.setAmount(new BigDecimal("200000"));
            req.setPaymentMethod("BANK");

            PaymentResponseDTO result = service.createPaymentQRCode(req);

            assertNotNull(result);
            assertEquals("linkId", result.getTransactionId());
            assertEquals("qr", result.getQrCode());
            assertEquals("VCB", result.getOwnerBank());
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }

        @Test
        void bookingNotFound_ThrowsException() {
            UUID bookingId = UUID.randomUUID();
            when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

            PaymentRequestDTO req = new PaymentRequestDTO();
            req.setBookingId(bookingId);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createPaymentQRCode(req));
            assertTrue(ex.getMessage().contains("Booking not found"));
        }

        @Test
        void missingProviderBank_ThrowsException() {
            UUID bookingId = UUID.randomUUID();
            Provider provider = new Provider(); // no bank/card
            ProviderAddress addr = new ProviderAddress();
            addr.setProvider(provider);
            Pitch pitch = new Pitch();
            pitch.setProviderAddress(addr);
            BookingDetail bd = new BookingDetail();
            bd.setPitch(pitch);

            Booking booking = Booking.builder()
                    .bookingId(bookingId).user(user).bookingDetails(List.of(bd)).build();
            when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

            PaymentRequestDTO req = new PaymentRequestDTO();
            req.setBookingId(bookingId);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createPaymentQRCode(req));
            assertTrue(ex.getMessage().contains("Provider or bank info missing"));
        }
    }

    @Nested
    class createShopPayment {
        @Test
        void bankMethod_callsPayOS() {
            Order order = Order.builder().orderId(42L).user(user).build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
            when(payOSService.createPayment(any(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(new PayOSService.PaymentResult("url", "linkId", "qr"));

            ShopPaymentRequestDTO req = new ShopPaymentRequestDTO();
            req.setUserId(userId);
            req.setAmount(new BigDecimal("100000"));
            req.setOrderCode(42L);
            req.setPaymentMethod("BANK");

            PaymentResponseDTO res = service.createShopPayment(req);

            assertEquals("linkId", res.getTransactionId());
            assertEquals("qr", res.getQrCode());
            assertEquals("PENDING", res.getStatus());
            verify(paymentRepository).save(any(Payment.class));
        }

        @Test
        void cashMethod_skipsPayOS() {
            Order order = Order.builder().orderId(42L).user(user).build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            ShopPaymentRequestDTO req = new ShopPaymentRequestDTO();
            req.setUserId(userId);
            req.setAmount(new BigDecimal("100000"));
            req.setOrderCode(42L);
            req.setPaymentMethod("CASH");

            PaymentResponseDTO res = service.createShopPayment(req);

            assertNotNull(res.getTransactionId());
            assertTrue(res.getTransactionId().startsWith("COD-"));
            verifyNoInteractions(payOSService);
        }

        @Test
        void missingOrderCode_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            ShopPaymentRequestDTO req = new ShopPaymentRequestDTO();
            req.setUserId(userId);
            req.setOrderCode(null);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createShopPayment(req));
            assertTrue(ex.getMessage().contains("Order ID is required"));
        }

        @Test
        void invalidPaymentMethod_ThrowsException() {
            Order order = Order.builder().orderId(42L).user(user).build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

            ShopPaymentRequestDTO req = new ShopPaymentRequestDTO();
            req.setUserId(userId);
            req.setAmount(new BigDecimal("100000"));
            req.setOrderCode(42L);
            req.setPaymentMethod("CRYPTO");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createShopPayment(req));
            assertTrue(ex.getMessage().contains("Invalid payment method"));
        }
    }

    @Nested
    class getPaymentsByUserId {
        @Test
        void hasData_ReturnsList() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            Payment p = Payment.builder()
                    .paymentId(1L)
                    .amount(new BigDecimal("100"))
                    .paymentStatus(PaymentStatus.PAID)
                    .transactionId("t1")
                    .checkoutUrl("u").build();
            when(paymentRepository.findByUser_UserId(userId)).thenReturn(List.of(p));

            List<PaymentResponseDTO> result = service.getPaymentsByUserId(userId);

            assertEquals(1, result.size());
            assertEquals("PAID", result.getFirst().getStatus());
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getPaymentsByUserId(userId));
            assertTrue(ex.getMessage().contains("User not found"));
        }
    }

    @Nested
    class processWebhook {
        @Test
        void successCode_marksPaidAndConfirmsOrder() {
            Order order = Order.builder()
                    .orderId(7L).user(user)
                    .status(OrderStatus.PENDING)
                    .items(List.of()) // no items
                    .build();
            Payment payment = Payment.builder()
                    .paymentId(1L)
                    .transactionId("link123")
                    .paymentStatus(PaymentStatus.PENDING)
                    .order(order).user(user)
                    .amount(BigDecimal.TEN)
                    .build();
            when(paymentRepository.findByTransactionId("link123")).thenReturn(Optional.of(payment));

            Map<String, Object> data = new HashMap<>();
            data.put("paymentLinkId", "link123");
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", "00");
            payload.put("data", data);

            service.processWebhook(payload);

            assertEquals(PaymentStatus.PAID, payment.getPaymentStatus());
            assertEquals(OrderStatus.CONFIRMED, order.getStatus());
            assertNotNull(order.getPaymentTime());
            verify(paymentRepository).save(payment);
            verify(orderRepository).save(order);
        }

        @Test
        void successCode_confirmsBooking_publishesEmail() {
            Booking booking = Booking.builder()
                    .bookingId(UUID.randomUUID()).user(user)
                    .status(BookingStatus.PENDING)
                    .paymentStatus(PaymentStatus.PENDING)
                    .build();
            Payment payment = Payment.builder()
                    .paymentId(1L)
                    .transactionId("link999")
                    .paymentStatus(PaymentStatus.PENDING)
                    .booking(booking).user(user)
                    .amount(BigDecimal.TEN)
                    .build();
            when(paymentRepository.findByTransactionId("link999")).thenReturn(Optional.of(payment));

            Map<String, Object> data = new HashMap<>();
            data.put("paymentLinkId", "link999");
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", "00");
            payload.put("data", data);

            service.processWebhook(payload);

            assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
            assertEquals(PaymentStatus.PAID, booking.getPaymentStatus());
            verify(rabbitTemplate, atLeastOnce())
                    .convertAndSend(anyString(), anyString(), anyString());
        }

        @Test
        void failureCode_cancelsOrderAndReleasesStock() {
            Order order = Order.builder()
                    .orderId(7L).user(user)
                    .status(OrderStatus.PENDING)
                    .items(List.of())
                    .build();
            Payment payment = Payment.builder()
                    .paymentId(1L)
                    .transactionId("link456")
                    .paymentStatus(PaymentStatus.PENDING)
                    .order(order).user(user)
                    .amount(BigDecimal.TEN)
                    .build();
            when(paymentRepository.findByTransactionId("link456")).thenReturn(Optional.of(payment));

            Map<String, Object> data = new HashMap<>();
            data.put("paymentLinkId", "link456");
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", "99");
            payload.put("data", data);

            service.processWebhook(payload);

            assertEquals(OrderStatus.CANCELED, order.getStatus());
            assertEquals(PaymentStatus.PENDING, payment.getPaymentStatus());
        }

        @Test
        void missingTransactionId_ignored() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", "00");
            payload.put("data", new HashMap<>());

            service.processWebhook(payload);

            verify(paymentRepository, never()).save(any());
        }

        @Test
        void paymentNotFound_skipsSave() {
            Map<String, Object> data = new HashMap<>();
            data.put("paymentLinkId", "missing");
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", "00");
            payload.put("data", data);
            when(paymentRepository.findByTransactionId("missing")).thenReturn(Optional.empty());

            service.processWebhook(payload);

            verify(paymentRepository, never()).save(any());
        }
    }

    @Nested
    class getPaymentStatusByBookingId {
        @Test
        void hasData_ReturnsResponseDTO() {
            UUID bookingId = UUID.randomUUID();
            Payment p = Payment.builder()
                    .amount(BigDecimal.TEN)
                    .transactionId("t").checkoutUrl("u")
                    .paymentStatus(PaymentStatus.PAID).build();
            when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                    .thenReturn(Optional.of(p));

            PaymentResponseDTO result = service.getPaymentStatusByBookingId(bookingId);

            assertEquals("PAID", result.getStatus());
        }

        @Test
        void notFound_ThrowsException() {
            UUID bookingId = UUID.randomUUID();
            when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                    .thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                    () -> service.getPaymentStatusByBookingId(bookingId));
        }
    }

    @Nested
    class getPaymentStatusByOrderId {
        @Test
        void hasData_ReturnsResponseDTO() {
            Payment p = Payment.builder()
                    .amount(BigDecimal.TEN)
                    .transactionId("t").checkoutUrl("u")
                    .paymentStatus(PaymentStatus.PAID).build();
            when(paymentRepository.findFirstByOrder_OrderIdOrderByCreatedAtDesc(7L))
                    .thenReturn(Optional.of(p));

            PaymentResponseDTO result = service.getPaymentStatusByOrderId(7L);

            assertEquals("PAID", result.getStatus());
        }

        @Test
        void notFound_ThrowsException() {
            when(paymentRepository.findFirstByOrder_OrderIdOrderByCreatedAtDesc(7L))
                    .thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                    () -> service.getPaymentStatusByOrderId(7L));
        }
    }
}