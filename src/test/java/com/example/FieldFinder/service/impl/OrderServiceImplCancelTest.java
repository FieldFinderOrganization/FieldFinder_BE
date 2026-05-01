package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.OrderItem;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.OrderItemRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplCancelTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock UserRepository userRepository;
    @Mock DiscountRepository discountRepository;
    @Mock UserDiscountRepository userDiscountRepository;
    @Mock RedissonClient redissonClient;
    @Mock ProductService productService;
    @Mock EmailService emailService;
    @Mock StockLockService stockLockService;
    @Mock PaymentRepository paymentRepository;
    @Mock RefundService refundService;

    OrderServiceImpl service;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(
                orderRepository, orderItemRepository, userRepository,
                discountRepository, userDiscountRepository,
                redissonClient, productService, emailService,
                stockLockService, paymentRepository, refundService);

        userId = UUID.randomUUID();
        user = new User();
        user.setUserId(userId);
    }

    private Order pendingOrder() {
        Product p = new Product();
        p.setProductId(7L);
        OrderItem item = OrderItem.builder()
                .product(p).size("M").quantity(2).price(50000.0)
                .build();
        Order order = Order.builder()
                .orderId(1L)
                .user(user)
                .totalAmount(100000.0)
                .status(OrderStatus.PENDING)
                .paymentMethod(PaymentMethod.BANK)
                .createdAt(LocalDateTime.now())
                .items(List.of(item))
                .build();
        item.setOrder(order);
        return order;
    }

    private Order paidOrder(LocalDateTime paymentTime) {
        Order o = pendingOrder();
        o.setStatus(OrderStatus.PAID);
        o.setPaymentTime(paymentTime);
        return o;
    }

    @Test
    void cancel_throws_whenOrderNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrderByUser(99L, userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void cancel_throws_whenOtherUser() {
        Order order = pendingOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrderByUser(1L, UUID.randomUUID(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("không có quyền");
    }

    @Test
    void cancel_throws_whenStatusNotEligible() {
        Order order = pendingOrder();
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrderByUser(1L, userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Chỉ có thể hủy");
    }

    @Test
    void cancel_throws_whenPaidPast24h() {
        Order order = paidOrder(LocalDateTime.now().minusHours(25));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrderByUser(1L, userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("24h");

        verifyNoInteractions(refundService);
    }

    @Test
    void cancel_pending_setsCanceledAndReleasesStock_noRefund() {
        Order order = pendingOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        service.cancelOrderByUser(1L, userId, "changed mind");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(productService).releaseStock(7L, "M", 2);
        verify(orderRepository).save(order);
        verify(emailService).sendOrderCancellation(order);
        verifyNoInteractions(refundService);
        verify(paymentRepository, never())
                .findFirstByOrder_OrderIdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void cancel_paidWithinWindow_issuesRefundAndMarksPaymentRefunded() {
        Order order = paidOrder(LocalDateTime.now().minusHours(5));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        Payment payment = Payment.builder()
                .paymentId(11L)
                .paymentStatus(PaymentStatus.PAID)
                .build();
        when(paymentRepository.findFirstByOrder_OrderIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(payment));
        when(refundService.issueRefundCredit(any(), any(), any(), any(), any()))
                .thenReturn(new RefundRequest());

        service.cancelOrderByUser(1L, userId, "wrong size");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(productService).releaseStock(7L, "M", 2);

        ArgumentCaptor<BigDecimal> amountCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(refundService).issueRefundCredit(
                eq(user), eq(RefundSourceType.ORDER), eq("1"),
                amountCap.capture(), reasonCap.capture());
        assertThat(amountCap.getValue()).isEqualByComparingTo("100000");
        assertThat(reasonCap.getValue()).isEqualTo("wrong size");

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProcessedAt()).isNotNull();
        verify(paymentRepository).save(payment);
        verify(emailService).sendOrderCancellation(order);
    }

    @Test
    void cancel_paidWithinWindow_zeroAmount_skipsRefundIssuance() {
        Order order = paidOrder(LocalDateTime.now().minusHours(2));
        order.setTotalAmount(0.0);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        service.cancelOrderByUser(1L, userId, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verifyNoInteractions(refundService);
    }

}