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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    @Override
    @Transactional
    public OrderResponseDTO createOrder(OrderRequestDTO request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found!"));

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING)
                .paymentMethod(PaymentMethod.valueOf(request.getPaymentMethod()))
                .createdAt(LocalDateTime.now())
                .build();

        order = orderRepository.save(order);

        double subTotal = 0.0;
        List<OrderItem> orderItemsToSave = new ArrayList<>();

        for (OrderItemRequestDTO itemDTO : request.getItems()) {
            Product product = productRepository.findById(itemDTO.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemDTO.getProductId()));

            // Tính giá đã áp dụng khuyến mãi cá nhân tại thời điểm mua
            // Lưu ý: Nếu muốn chính xác tuyệt đối, nên gọi lại logic tính giá từ ProductService
            // Nhưng ở đây ta dùng getEffectivePrice() mặc định (hoặc giá từ FE gửi lên nếu cần validate)
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
        }
        orderItemRepository.saveAll(orderItemsToSave);

        double totalDiscountAmount = 0.0;

        if (request.getDiscountCodes() != null && !request.getDiscountCodes().isEmpty()) {
            for (String code : request.getDiscountCodes()) {
                Discount discount = discountRepository.findByCode(code)
                        .orElseThrow(() -> new RuntimeException("Discount code not found: " + code));

                // Tìm trong ví user (hoặc tạo mới nếu là mã global chưa lưu ví)
                UserDiscount userDiscount = userDiscountRepository.findByUserAndDiscount(user, discount)
                        .orElse(null);

                // Nếu chưa có trong ví, kiểm tra xem có phải mã Global public không
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

                // QUAN TRỌNG: Đánh dấu đã sử dụng để ProductService lọc ra sau này
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
            // PERCENTAGE
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
                .userName(order.getUser().getName())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .paymentMethod(order.getPaymentMethod().name())
                .createdAt(order.getCreatedAt())
                .items(items)
                .build();
    }
}