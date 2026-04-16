package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.dto.req.OrderItemRequestDTO;
import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderItemResponseDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.OrderService;
import com.example.FieldFinder.service.ProductService;
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
import java.util.List;
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
                .createdAt(LocalDateTime.now())
                .build();

        order = orderRepository.save(order);

        double subTotal = 0.0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();

        // Theo dõi các item đã lock thành công để compensation nếu lỗi giữa chừng
        List<LockedItem> lockedItems = new ArrayList<>();

        for (OrderItemRequestDTO itemDTO : request.getItems()) {
            String lockKey = "lock_product_" + itemDTO.getProductId() + "_size_" + itemDTO.getSize();
            RLock lock = redissonClient.getLock(lockKey);
            boolean isLocked = false;
            boolean redisAvailable = true;

            try {
                // 1. Attempt Redis Lock
                try {
                    isLocked = lock.tryLock(3, 10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // Re-throw to be caught by the outer InterruptedException catch
                    throw e;
                } catch (Exception redisEx) {
                    System.err.println("⚠️ Redis unavailable, falling back to DB lock: " + redisEx.getMessage());
                    redisAvailable = false;
                }

                // 2. Process Stock
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

                // Tạo OrderItem từ kết quả lock
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

        double totalDiscountAmount = 0.0;

        try {
            if (request.getDiscountCodes() != null && !request.getDiscountCodes().isEmpty() && user != null) {
                for (String code : request.getDiscountCodes()) {
                    Discount discount = discountRepository.findByCode(code)
                            .orElseThrow(() -> new RuntimeException("Discount code not found: " + code));

                    UserDiscount userDiscount = userDiscountRepository.findByUserAndDiscount(user, discount)
                            .orElse(null);

                    if (userDiscount == null) {
                        if (discount.getScope() == Discount.DiscountScope.GLOBAL) {
                            userDiscount = UserDiscount.builder()
                                    .user(user)
                                    .discount(discount)
                                    .savedAt(LocalDateTime.now())
                                    .isUsed(false)
                                    .build();
                            userDiscountRepository.save(userDiscount);
                        } else {
                            throw new RuntimeException("You don't own this voucher: " + code);
                        }
                    }

                    if (userDiscount.isUsed()) {
                        throw new RuntimeException("Voucher has already been used: " + code);
                    }
                    if (discount.getStatus() != Discount.DiscountStatus.ACTIVE ||
                            LocalDate.now().isAfter(discount.getEndDate()) ||
                            LocalDate.now().isBefore(discount.getStartDate())) {
                        throw new RuntimeException("Voucher is not active or expired: " + code);
                    }
                    if (discount.getMinOrderValue() != null &&
                            BigDecimal.valueOf(subTotal).compareTo(discount.getMinOrderValue()) < 0) {
                        throw new RuntimeException("Order value is not enough for voucher: " + code);
                    }

                    double discountValueForCode = calculateDiscountAmount(discount, orderItemsToSave, subTotal);
                    totalDiscountAmount += discountValueForCode;

                    userDiscount.setUsed(true);
                    userDiscount.setUsedAt(LocalDateTime.now());
                    userDiscountRepository.save(userDiscount);
                }
            }
        } catch (RuntimeException e) {
            // Discount validation thất bại sau khi đã lock stock → hoàn tác
            compensateLockedItems(lockedItems);
            throw e;
        }

        double finalAmount = Math.max(0, subTotal - totalDiscountAmount);
        order.setTotalAmount(finalAmount);
        order.setItems(orderItemsToSave);

        orderRepository.save(order);

        if (order.getPaymentMethod() == PaymentMethod.CASH) {
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
        }

        final Order finalOrder = order;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
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
                System.err.println("❌ Lỗi khi hoàn tác lock tồn kho product="
                        + item.productId() + " size=" + item.size() + ": " + ex.getMessage());
            }
        }
    }

    private double calculateDiscountAmount(Discount discount, List<OrderItem> items, double orderSubTotal) {
        double applicableAmount = 0.0;

        if (discount.getScope() == Discount.DiscountScope.GLOBAL) {
            applicableAmount = orderSubTotal;
        } else if (discount.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            List<Long> applicableProductIds = discount.getApplicableProducts().stream()
                    .map(Product::getProductId).toList();

            for (OrderItem item : items) {
                if (applicableProductIds.contains(item.getProduct().getProductId())) {
                    applicableAmount += item.getPrice();
                }
            }
        } else if (discount.getScope() == Discount.DiscountScope.CATEGORY) {
            List<Long> applicableCategoryIds = discount.getApplicableCategories().stream()
                    .map(Category::getCategoryId).toList();

            for (OrderItem item : items) {
                Category prodCat = item.getProduct().getCategory();
                if (prodCat != null && applicableCategoryIds.contains(prodCat.getCategoryId())) {
                    applicableAmount += item.getPrice();
                }
            }
        }

        if (applicableAmount == 0)
            return 0.0;

        double calculatedDiscount = 0.0;
        BigDecimal val = discount.getValue();

        if (discount.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
            calculatedDiscount = val.doubleValue();
            if (calculatedDiscount > applicableAmount) {
                calculatedDiscount = applicableAmount;
            }
        } else {
            calculatedDiscount = (applicableAmount * val.doubleValue()) / 100.0;
            if (discount.getMaxDiscountAmount() != null) {
                double max = discount.getMaxDiscountAmount().doubleValue();
                if (calculatedDiscount > max) {
                    calculatedDiscount = max;
                }
            }
        }

        return calculatedDiscount;
    }

    @Override
    public OrderResponseDTO getOrderById(Long id) {
        return orderRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new RuntimeException("Order not found!"));
    }

    @Override
    public List<OrderResponseDTO> getAllOrders() {
        return orderRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public OrderResponseDTO updateOrderStatus(Long id, String status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found!"));

        order.setStatus(OrderStatus.valueOf(status));
        orderRepository.save(order);
        return mapToResponse(order);
    }

    @Override
    public void deleteOrder(Long id) {
        orderRepository.deleteById(id);
    }

    @Override
    @Transactional
    public OrderResponseDTO cancelOrderByUser(Long orderId, UUID userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found!"));

        if (order.getUser() == null || !order.getUser().getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền hủy đơn hàng này!");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new RuntimeException("Chỉ có thể hủy đơn hàng đang ở trạng thái PENDING!");
        }

        order.setStatus(OrderStatus.CANCELED);
        order.setUpdatedAt(LocalDateTime.now());

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

        try {
            emailService.sendOrderCancellation(order);
        } catch (Exception e) {
            System.err.println("Lỗi gửi email hủy đơn hàng #" + order.getOrderId() + ": " + e.getMessage());
        }

        return mapToResponse(order);
    }

    @Override
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
                .items(items)
                .build();
    }

    @Scheduled(fixedRate = 3600000) // 1h
    @Transactional
    public void processAutomatedOrderManagement() {
        System.out.println("Starting Automated Order Management (Cleaning stale PENDING orders)...");
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        List<Order> staleOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, threshold);

        int count = 0;
        for (Order order : staleOrders) {
            System.out.println("Cancelling stale Order #" + order.getOrderId());
            order.setStatus(OrderStatus.CANCELED);

            // Release stock
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

            // Send email
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