package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.dto.req.OrderItemRequestDTO;
import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderItemResponseDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;

    private final ProductVariantRepository productVariantRepository;
    private final RedissonClient redissonClient;

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

        // Vòng lặp xử lý từng sản phẩm với Lock phân tán
        for (OrderItemRequestDTO itemDTO : request.getItems()) {

            // 1. Tạo ổ khóa duy nhất cho Sản phẩm + Size
            String lockKey = "lock_product_" + itemDTO.getProductId() + "_size_" + itemDTO.getSize();
            RLock lock = redissonClient.getLock(lockKey);

            boolean isLocked = false;
            try {
                // 2. Cố gắng lấy khóa (Chờ tối đa 3 giây, giữ khóa tối đa 10 giây nếu server sập)
                isLocked = lock.tryLock(3, 10, TimeUnit.SECONDS);

                if (isLocked) {
                    Product product = productRepository.findById(itemDTO.getProductId())
                            .orElseThrow(() -> new RuntimeException("Product not found: " + itemDTO.getProductId()));

                    // A. Đọc tồn kho thực tế TỪ DATABASE
                    ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(itemDTO.getProductId(), itemDTO.getSize())
                            .orElseThrow(() -> new RuntimeException("Không tìm thấy size " + itemDTO.getSize() + " cho sản phẩm " + product.getName()));

                    // B. Kiểm tra cháy hàng
                    if (variant.getAvailableQuantity() < itemDTO.getQuantity()) {
                        throw new RuntimeException("Rất tiếc! Sản phẩm " + product.getName() + " (Size " + itemDTO.getSize() + ") vừa hết hàng hoặc không đủ số lượng.");
                    }

                    // C. CHỈ GIỮ HÀNG (Khóa tạm thời) chứ chưa trừ hẳn kho
                    variant.setLockedQuantity(variant.getLockedQuantity() + itemDTO.getQuantity());
                    productVariantRepository.saveAndFlush(variant);

                    // D. Tính toán tiền và tạo OrderItem
                    double price = product.getEffectivePrice() * itemDTO.getQuantity();
                    subTotal += price;

                    OrderItem orderItem = OrderItem.builder()
                            .order(order)
                            .product(product)
                            .quantity(itemDTO.getQuantity())
                            .price(price)
                            .size(itemDTO.getSize())
                            .build();

                    orderItemsToSave.add(orderItem);
                } else {
                    // Nếu 3 giây vẫn không lấy được khóa (quá nhiều người mua cùng lúc)
                    throw new RuntimeException("Hệ thống đang quá tải đối với sản phẩm này, vui lòng thử lại sau giây lát!");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Lỗi hệ thống khi xử lý tồn kho!");
            } finally {
                // 3. TRẢ KHÓA LẠI CHO HỆ THỐNG
                if (isLocked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        orderItemRepository.saveAll(orderItemsToSave);

        double totalDiscountAmount = 0.0;

        if (request.getDiscountCodes() != null && !request.getDiscountCodes().isEmpty() && user != null) {
            for (String code : request.getDiscountCodes()) {
                Discount discount = discountRepository.findByCode(code)
                        .orElseThrow(() -> new RuntimeException("Discount code not found: " + code));

                UserDiscount userDiscount = userDiscountRepository.findByUserAndDiscount(user, discount)
                        .orElse(null);

                if (userDiscount == null) {
                    if(discount.getScope() == Discount.DiscountScope.GLOBAL) {
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

        double finalAmount = Math.max(0, subTotal - totalDiscountAmount);
        order.setTotalAmount(finalAmount);
        order.setItems(orderItemsToSave);

        orderRepository.save(order);

        return mapToResponse(order);
    }

    private double calculateDiscountAmount(Discount discount, List<OrderItem> items, double orderSubTotal) {
        double applicableAmount = 0.0;

        if (discount.getScope() == Discount.DiscountScope.GLOBAL) {
            applicableAmount = orderSubTotal;
        }
        else if (discount.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            List<Long> applicableProductIds = discount.getApplicableProducts().stream()
                    .map(Product::getProductId).toList();

            for (OrderItem item : items) {
                if (applicableProductIds.contains(item.getProduct().getProductId())) {
                    applicableAmount += item.getPrice();
                }
            }
        }
        else if (discount.getScope() == Discount.DiscountScope.CATEGORY) {
            List<Long> applicableCategoryIds = discount.getApplicableCategories().stream()
                    .map(Category::getCategoryId).toList();

            for (OrderItem item : items) {
                Category prodCat = item.getProduct().getCategory();
                if (prodCat != null && applicableCategoryIds.contains(prodCat.getCategoryId())) {
                    applicableAmount += item.getPrice();
                }
            }
        }

        if (applicableAmount == 0) return 0.0;

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
                .items(items)
                .build();
    }
}