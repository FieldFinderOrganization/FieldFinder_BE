package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.dto.req.OrderItemRequestDTO;
import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderItemResponseDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.OrderService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.util.DiscountEligibilityUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private record LockedItem(Long productId, String size, int quantity) {}

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;

    private final RedissonClient redissonClient;
    private final ProductService productService;
    private final EmailService emailService;
    private final StockLockService stockLockService;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;

    private static final long ORDER_REFUND_WINDOW_HOURS = 24;

    @Override
    @Transactional
    public OrderResponseDTO createOrder(OrderRequestDTO request) {
        User user = null;
        if (!"GUEST".equals(request.getUserId())) {
            user = userRepository.findById(UUID.fromString(String.valueOf(request.getUserId())))
                    .orElseThrow(() -> new RuntimeException("User not found!"));
        }

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING)
                .paymentMethod(PaymentMethod.valueOf(request.getPaymentMethod()))
                .deliveryAddress(request.getDeliveryAddress())
                .destLat(request.getDestLat())
                .destLng(request.getDestLng())
                .createdAt(LocalDateTime.now())
                .build();

        order = orderRepository.save(order);

        double subTotal = 0.0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();

        List<LockedItem> lockedItems = new ArrayList<>();

        for (OrderItemRequestDTO itemDTO : request.getItems()) {
            String lockKey = "lock_product_" + itemDTO.getProductId() + "_size_" + itemDTO.getSize();
            RLock lock = redissonClient.getLock(lockKey);
            boolean isLocked = false;
            boolean redisAvailable = true;

            try {
                try {
                    isLocked = lock.tryLock(3, 10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception redisEx) {
                    System.err.println("Redis unavailable, falling back to DB lock: " + redisEx.getMessage());
                    redisAvailable = false;
                }

                if (redisAvailable && !isLocked) {
                    throw new RuntimeException("Hệ thống đang quá tải, vui lòng thử lại sau!");
                }

                StockLockService.LockResult result;
                if (redisAvailable) {
                    result = stockLockService.lockStock(itemDTO.getProductId(), itemDTO.getSize(), itemDTO.getQuantity());
                } else {
                    result = stockLockService.lockStockWithDbLock(itemDTO.getProductId(), itemDTO.getSize(), itemDTO.getQuantity());
                }

                lockedItems.add(new LockedItem(itemDTO.getProductId(), itemDTO.getSize(), itemDTO.getQuantity()));

                OrderItem orderItem = OrderItem.builder()
                        .order(order)
                        .product(result.product())
                        .size(itemDTO.getSize())
                        .quantity(itemDTO.getQuantity())
                        .price(result.price())
                        .build();
                orderItemsToSave.add(orderItem);
                subTotal += result.price();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                compensateLockedItems(lockedItems);
                throw new RuntimeException("Lỗi hệ thống khi xử lý tồn kho!");
            } catch (RuntimeException e) {
                compensateLockedItems(lockedItems);
                throw e;
            } finally {
                if (isLocked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        orderItemRepository.saveAll(orderItemsToSave);

        double finalAmount;
        try {
            finalAmount = applyDiscountsTwoPhase(
                    request.getDiscountCodes(), user, orderItemsToSave, subTotal);
        } catch (RuntimeException e) {
            compensateLockedItems(lockedItems);
            throw e;
        }

        order.setTotalAmount(finalAmount);
        order.setItems(orderItemsToSave);

        orderRepository.save(order);

        final List<Long> orderProductIds = orderItemsToSave.stream()
                .map(oi -> oi.getProduct().getProductId())
                .distinct()
                .toList();

        if (order.getPaymentMethod() == PaymentMethod.CASH) {
            System.out.println("[createOrder] CASH branch entered for orderId=" + order.getOrderId()
                    + " items=" + orderItemsToSave.size());
            order.setStatus(OrderStatus.CONFIRMED);
            order.setPaymentTime(order.getCreatedAt());
            for (OrderItem item : orderItemsToSave) {
                productService.commitStock(
                        item.getProduct().getProductId(),
                        item.getSize(),
                        item.getQuantity()
                );
            }
            orderRepository.save(order);
            System.out.println("[createOrder] CASH branch finished for orderId=" + order.getOrderId());
        }

        final Order finalOrder = order;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Sau commit: xóa cache list + chi tiết theo từng SP (không flush cả product_detail).
                productService.evictAllListProductCaches();
                for (Long pid : orderProductIds) {
                    productService.evictProductDetailForId(pid);
                }

                if (finalOrder.getPaymentMethod() == PaymentMethod.CASH) {
                    emailService.sendOrderConfirmation(finalOrder);
                } else if (finalOrder.getPaymentMethod() == PaymentMethod.BANK) {
                    emailService.sendOrderPaymentReminder(finalOrder);
                }
            }
        });

        return mapToResponse(order);
    }

    private void compensateLockedItems(List<LockedItem> lockedItems) {
        for (LockedItem item : lockedItems) {
            try {
                stockLockService.unlockStock(item.productId(), item.size(), item.quantity());
            } catch (Exception ex) {
                System.err.println("Lỗi khi hoàn tác lock tồn kho product="
                        + item.productId() + " size=" + item.size() + ": " + ex.getMessage());
            }
        }
    }

    private double applyDiscountsTwoPhase(
            List<String> codes,
            User user,
            List<OrderItem> orderItemsToSave,
            double subTotal) {

        if (codes == null || codes.isEmpty() || user == null) {
            return Math.max(0, subTotal);
        }

        // Validate + load discounts
        List<Discount> discounts = new ArrayList<>();
        List<UserDiscount> userDiscounts = new ArrayList<>();
        for (String code : codes) {
            Discount discount = discountRepository.findByCode(code)
                    .orElseThrow(() -> new RuntimeException("Discount code not found: " + code));

            UserDiscount userDiscount = userDiscountRepository.findByUserAndDiscount(user, discount)
                    .orElse(null);

            if (userDiscount == null) {
                if (discount.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) {
                    throw new RuntimeException("Mã hoàn tiền không thuộc về người dùng này: " + code);
                }
                userDiscount = UserDiscount.builder()
                        .user(user).discount(discount)
                        .savedAt(LocalDateTime.now()).isUsed(false).build();
                userDiscountRepository.save(userDiscount);
            }

            if (userDiscount.isUsed()) {
                throw new RuntimeException("Voucher has already been used: " + code);
            }
            if (!DiscountEligibilityUtil.isUsable(discount, LocalDate.now())) {
                throw new RuntimeException("Voucher is not active or expired: " + code);
            }
            discounts.add(discount);
            userDiscounts.add(userDiscount);
        }

        // Phase 1: SPECIFIC_PRODUCT + CATEGORY (best-wins per item)
        Map<Integer, Double> itemDiscountMap = new HashMap<>();
        for (Discount d : discounts) {
            if (d.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) continue;
            if (d.getScope() == Discount.DiscountScope.GLOBAL) continue;
            boolean hasEligibleItem = false;
            for (int i = 0; i < orderItemsToSave.size(); i++) {
                OrderItem item = orderItemsToSave.get(i);
                if (DiscountEligibilityUtil.isEligibleForOrderItem(d, item)) {
                    hasEligibleItem = true;
                    double amt = calcDiscountForAmount(d, item.getPrice());
                    itemDiscountMap.merge(i, amt, Math::max);
                }
            }
            if (!hasEligibleItem) {
                throw new RuntimeException("Order item value is not enough for voucher: " + d.getCode());
            }
        }
        double specificTotal = itemDiscountMap.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        double subAfterSpecific = subTotal - specificTotal;

        // Phase 2: GLOBAL promo
        double globalTotal = 0.0;
        for (Discount d : discounts) {
            if (d.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) continue;
            if (d.getScope() != Discount.DiscountScope.GLOBAL) continue;
            if (d.getMinOrderValue() != null &&
                    BigDecimal.valueOf(subAfterSpecific).compareTo(d.getMinOrderValue()) < 0) {
                throw new RuntimeException("Order value is not enough for voucher: " + d.getCode());
            }
            globalTotal += calcDiscountForAmount(d, subAfterSpecific);
        }

        double afterPromo = Math.max(0, subAfterSpecific - globalTotal);

        // Phase 3: REFUND_CREDIT trừ trực tiếp lên afterPromo, hỗ trợ residual
        for (int i = 0; i < discounts.size(); i++) {
            Discount d = discounts.get(i);
            if (d.getKind() != com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) continue;
            UserDiscount ud = userDiscounts.get(i);
            BigDecimal balance = ud.getRemainingValue() != null
                    ? ud.getRemainingValue() : d.getValue();
            if (balance.signum() <= 0) continue;
            double deduct = Math.min(afterPromo, balance.doubleValue());
            afterPromo -= deduct;
            BigDecimal newBalance = balance.subtract(BigDecimal.valueOf(deduct));
            ud.setRemainingValue(newBalance);
            if (newBalance.signum() <= 0) {
                ud.setUsed(true);
                ud.setUsedAt(LocalDateTime.now());
            }
            userDiscountRepository.save(ud);
            if (afterPromo <= 0) break;
        }

        for (int i = 0; i < discounts.size(); i++) {
            Discount d = discounts.get(i);
            if (d.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) continue;
            UserDiscount ud = userDiscounts.get(i);
            ud.setUsed(true);
            ud.setUsedAt(LocalDateTime.now());
            userDiscountRepository.save(ud);
        }

        return Math.max(0, afterPromo);
    }

    private double calcDiscountForAmount(Discount d, double base) {
        if (base <= 0) return 0.0;
        BigDecimal val = d.getValue();
        double result;
        if (d.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
            result = val.doubleValue();
        } else {
            result = base * val.doubleValue() / 100.0;
            if (d.getMaxDiscountAmount() != null) {
                double max = d.getMaxDiscountAmount().doubleValue();
                if (result > max) result = max;
            }
        }
        if (result > base) result = base;
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponseDTO getOrderById(Long id) {
        return orderRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new RuntimeException("Order not found!"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> getAllOrders() {
        return orderRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public OrderResponseDTO updateOrderStatus(Long id, String status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found!"));

        OrderStatus newStatus = OrderStatus.valueOf(status);
        order.setStatus(newStatus);
        order.setUpdatedAt(LocalDateTime.now());

        // COD: khi giao xong coi như đã thu tiền mặt -> đánh dấu Payment PAID (nếu có row).
        if (newStatus == OrderStatus.DELIVERED
                && order.getPaymentMethod() == PaymentMethod.CASH) {
            order.setPaymentTime(LocalDateTime.now());
            paymentRepository
                    .findFirstByOrder_OrderIdOrderByCreatedAtDesc(order.getOrderId())
                    .ifPresent(p -> {
                        p.setPaymentStatus(PaymentStatus.PAID);
                        p.setProcessedAt(LocalDateTime.now());
                        paymentRepository.save(p);
                    });
        }

        orderRepository.save(order);
        return mapToResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> getAvailableOrdersForShipper() {
        return orderRepository.findByStatusAndShipperIsNullOrderByOrderIdDesc(OrderStatus.CONFIRMED)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponseDTO claimOrder(Long orderId, UUID shipperId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found!"));

        if (order.getShipper() != null) {
            throw new RuntimeException("Đơn hàng đã có shipper nhận!");
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new RuntimeException("Chỉ nhận được đơn ở trạng thái CONFIRMED!");
        }

        User shipper = userRepository.findById(shipperId)
                .orElseThrow(() -> new RuntimeException("Shipper not found!"));

        order.setShipper(shipper);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return mapToResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> getOrdersByShipperId(UUID shipperId) {
        User shipper = userRepository.findById(shipperId)
                .orElseThrow(() -> new RuntimeException("Shipper not found!"));
        return orderRepository.findByShipperOrderByOrderIdDesc(shipper)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteOrder(Long id) {
        orderRepository.deleteById(id);
    }

    @Override
    @Transactional
    public OrderResponseDTO cancelOrderByUser(Long orderId, UUID userId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found!"));

        if (order.getUser() == null || !order.getUser().getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền hủy đơn hàng này!");
        }

        boolean isPending = order.getStatus() == OrderStatus.PENDING;
        boolean isPaidWithinWindow = isPaidOrder(order)
                && order.getPaymentTime() != null
                && order.getPaymentTime().plusHours(ORDER_REFUND_WINDOW_HOURS).isAfter(LocalDateTime.now());

        if (!isPending && !isPaidWithinWindow) {
            throw new RuntimeException(
                    "Chỉ có thể hủy đơn PENDING hoặc đơn đã thanh toán trong vòng "
                            + ORDER_REFUND_WINDOW_HOURS + "h!");
        }

        boolean wasCommitted = isPaidOrder(order);

        order.setStatus(OrderStatus.CANCELED);
        order.setUpdatedAt(LocalDateTime.now());

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (wasCommitted) {
                    productService.restoreStock(
                            item.getProduct().getProductId(),
                            item.getSize(),
                            item.getQuantity()
                    );
                } else {
                    productService.releaseStock(
                            item.getProduct().getProductId(),
                            item.getSize(),
                            item.getQuantity()
                    );
                }
            }
        }

        orderRepository.save(order);

        if (isPaidWithinWindow) {
            BigDecimal refundAmount = BigDecimal.valueOf(
                    order.getTotalAmount() != null ? order.getTotalAmount() : 0.0);
            if (refundAmount.signum() > 0) {
                refundService.issueRefundCredit(
                        order.getUser(),
                        RefundSourceType.ORDER,
                        String.valueOf(order.getOrderId()),
                        refundAmount,
                        reason != null && !reason.isBlank()
                                ? reason
                                : "User cancel order within 24h refund window");

                paymentRepository
                        .findFirstByOrder_OrderIdOrderByCreatedAtDesc(order.getOrderId())
                        .ifPresent(p -> {
                            p.setPaymentStatus(PaymentStatus.REFUNDED);
                            p.setProcessedAt(LocalDateTime.now());
                            paymentRepository.save(p);
                        });
            }
        }

        try {
            emailService.sendOrderCancellation(order);
        } catch (Exception e) {
            System.err.println(" Lỗi gửi email hủy đơn hàng #" + order.getOrderId() + ": " + e.getMessage());
        }

        productService.evictAllListProductCaches();

        return mapToResponse(order);
    }

    private boolean isPaidOrder(Order order) {
        OrderStatus s = order.getStatus();
        return s == OrderStatus.PAID || s == OrderStatus.CONFIRMED;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponseDTO> getOrdersByUserId(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found!"));

        List<Order> orders = orderRepository.findByUser(user);

        return orders.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private OrderResponseDTO mapToResponse(Order order) {
        List<OrderItemResponseDTO> items = order.getItems().stream()
                .map(item -> OrderItemResponseDTO.builder()
                        .productId(item.getProduct().getProductId())
                        .productName(item.getProduct().getName())
                        .quantity(item.getQuantity())
                        .price(item.getPrice())
                        .imageUrl(item.getProduct().getImageUrl())
                        .size(item.getSize())
                        .build())
                .toList();

        return OrderResponseDTO.builder()
                .orderId(order.getOrderId())
                .userName(order.getUser() != null ? order.getUser().getName() : "Guest")
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .paymentMethod(order.getPaymentMethod().name())
                .createdAt(order.getCreatedAt())
                .paymentTime(order.getPaymentTime())
                .deliveryAddress(order.getDeliveryAddress())
                .destLat(order.getDestLat())
                .destLng(order.getDestLng())
                .shipperName(order.getShipper() != null ? order.getShipper().getName() : null)
                .items(items)
                .build();
    }

    @Scheduled(fixedRate = 3600000) // 1h
    @Transactional
    public void processAutomatedOrderManagement() {
        System.out.println(" Starting Automated Order Management (Cleaning stale PENDING orders)...");
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        List<Order> staleOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, threshold);

        int count = 0;
        for (Order order : staleOrders) {
            System.out.println(" Cancelling stale Order #" + order.getOrderId());
            order.setStatus(OrderStatus.CANCELED);

            if (order.getItems() != null) {
                for (OrderItem item : order.getItems()) {
                    productService.releaseStock(
                            item.getProduct().getProductId(),
                            item.getSize(),
                            item.getQuantity()
                    );
                }
            }

            orderRepository.save(order);
            count++;

            productService.evictAllListProductCaches();

            try {
                emailService.sendOrderCancellation(order);
            } catch (Exception e) {
                System.err.println(" Lỗi gửi email hủy đơn hàng #" + order.getOrderId() + ": " + e.getMessage());
            }
        }
        if (count > 0) {
            System.out.println(" Processed and cancelled " + count + " stale orders.");
        }
    }
}