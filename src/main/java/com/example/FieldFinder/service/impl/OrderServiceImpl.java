package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.dto.req.OrderItemRequestDTO;
import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderItemResponseDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.dto.res.ShipperEarningsDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.OrderService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.service.UserTierService;
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
    private final com.example.FieldFinder.service.BankAccountService bankAccountService;
    private final UserTierService userTierService;
    private final com.example.FieldFinder.service.DiscountUsageService discountUsageService;
    private final com.example.FieldFinder.service.PointService pointService;
    private final com.example.FieldFinder.service.NotificationService notificationService;
    private final com.example.FieldFinder.service.DeliveryFeeService deliveryFeeService;
    private final com.example.FieldFinder.service.ShipperWalletService shipperWalletService;

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
                    request.getDiscountCodes(), user, orderItemsToSave, subTotal, order);
        } catch (RuntimeException e) {
            compensateLockedItems(lockedItems);
            throw e;
        }

        // Phí ship tính server-side theo khoảng cách kho -> điểm giao (không tin số client gửi).
        double shippingFee = 0.0;
        double grossShippingFee = 0.0;
        if (request.getDestLat() != null && request.getDestLng() != null) {
            var quote = deliveryFeeService
                    .quote(request.getDestLat(), request.getDestLng(), finalAmount);
            shippingFee = quote.fee();          // khách trả (0 nếu freeship)
            grossShippingFee = quote.grossFee(); // phí gốc → thu nhập shipper
        }
        order.setShippingFee(shippingFee);
        order.setGrossShippingFee(grossShippingFee);
        order.setTotalAmount(finalAmount + shippingFee);
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

            if (user != null) {
                userTierService.recalcTier(user.getUserId());
            }
        }

        final Order finalOrder = order;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Thông báo "đặt hàng thành công" cho MỌI đơn có user (CASH + BANK + đặt qua AI —
                // AI cũng chui vào createOrder). Chạy ĐẦU TIÊN và tự bọc try/catch để các side-effect
                // best-effort phía dưới (xóa cache Redis, email) lỗi cũng không nuốt mất thông báo.
                // ORDER_CONFIRMED giờ chỉ phát ở đường xác nhận thanh toán / đổi trạng thái.
                if (finalOrder.getUser() != null) {
                    try {
                        String ref = String.valueOf(finalOrder.getOrderId());
                        String body = finalOrder.getPaymentMethod() == PaymentMethod.BANK
                                ? "Đơn hàng #" + ref + " đã được tạo, vui lòng hoàn tất thanh toán."
                                : "Đơn hàng #" + ref + " đã đặt thành công và đang được chuẩn bị.";
                        notificationService.notify(finalOrder.getUser().getUserId(),
                                "ORDER_PLACED", "Đặt hàng thành công", body,
                                "ORDER", ref);
                    } catch (Exception e) {
                        System.err.println("Không tạo được thông báo đặt hàng #"
                                + finalOrder.getOrderId() + ": " + e.getMessage());
                    }
                }

                // Sau commit: xóa cache list + chi tiết theo từng SP (không flush cả product_detail).
                // Lỗi Redis ở đây không được phá phần còn lại của callback.
                try {
                    productService.evictAllListProductCaches();
                    for (Long pid : orderProductIds) {
                        productService.evictProductDetailForId(pid);
                    }
                } catch (Exception e) {
                    System.err.println("Không xóa được cache sản phẩm sau đặt đơn #"
                            + finalOrder.getOrderId() + ": " + e.getMessage());
                }

                // Email đã là @Async nhưng vẫn bọc cho chắc.
                try {
                    if (finalOrder.getPaymentMethod() == PaymentMethod.CASH) {
                        emailService.sendOrderConfirmation(finalOrder);
                    } else if (finalOrder.getPaymentMethod() == PaymentMethod.BANK) {
                        emailService.sendOrderPaymentReminder(finalOrder);
                    }
                } catch (Exception e) {
                    System.err.println("Không gửi được email đơn hàng #"
                            + finalOrder.getOrderId() + ": " + e.getMessage());
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
            double subTotal,
            Order order) {

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

            // Mã hoàn do provider hủy đặt sân phát hành: chỉ dùng đặt sân, không dùng cho sản phẩm
            if (discount.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT
                    && discount.getRestrictProviderId() != null) {
                throw new RuntimeException(
                        "Mã hoàn tiền này chỉ dùng cho đặt sân, không áp dụng cho sản phẩm: " + code);
            }

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
            // Chặn cả voucher đã nằm sẵn trong ví từ trước khi user bị xuống hạng
            if (discount.getMinTier() != null
                    && !user.getEffectiveTier().isAtLeast(discount.getMinTier())) {
                throw new RuntimeException("Voucher chỉ dành cho hạng "
                        + discount.getMinTier().name() + " trở lên: " + code);
            }
            discounts.add(discount);
            userDiscounts.add(userDiscount);
        }

        // Server-authoritative: tối đa 1 mã GLOBAL / đơn (FE đã chặn nhưng API có thể bị
        // gọi thẳng). Nhiều GLOBAL sẽ cộng dồn ở Phase 2 -> giảm vượt margin.
        long globalCount = discounts.stream()
                .filter(d -> d.getKind() != com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT)
                .filter(d -> d.getScope() == Discount.DiscountScope.GLOBAL)
                .count();
        if (globalCount > 1) {
            throw new RuntimeException("Chỉ được áp dụng 1 mã giảm toàn đơn (GLOBAL) cho mỗi đơn hàng");
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
            // Ghi lượt dùng để hoàn đúng số dư nếu đơn bị hủy
            discountUsageService.recordForOrder(ud, order.getOrderId(), BigDecimal.valueOf(deduct));
            if (afterPromo <= 0) break;
        }

        for (int i = 0; i < discounts.size(); i++) {
            Discount d = discounts.get(i);
            if (d.getKind() == com.example.FieldFinder.Enum.DiscountKind.REFUND_CREDIT) continue;
            // quantity = tổng lượt dùng; trừ atomic tại thời điểm dùng, 0 row = hết lượt
            if (discountRepository.decrementQuantity(d.getDiscountId()) == 0) {
                throw new RuntimeException("Voucher đã hết lượt sử dụng: " + d.getCode());
            }
            UserDiscount ud = userDiscounts.get(i);
            ud.setUsed(true);
            ud.setUsedAt(LocalDateTime.now());
            userDiscountRepository.save(ud);
            // Ghi lượt dùng để hoàn lại (un-use + quantity+1) nếu đơn bị hủy
            discountUsageService.recordForOrder(ud, order.getOrderId(), null);
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
        OrderStatus oldStatus = order.getStatus();

        // Đơn đã giao là trạng thái cuối — không cho hủy ngược (chưa có flow trả hàng/hoàn về shop).
        // Tránh đảo sai ví shipper (ship đã credit, công nợ COD đã ghi), điểm/voucher/tồn kho.
        if (oldStatus == OrderStatus.DELIVERED && newStatus == OrderStatus.CANCELED) {
            throw new RuntimeException("Đơn đã giao không thể hủy. Cần flow trả hàng/hoàn tiền riêng.");
        }

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

        // Admin hủy tay cũng phải hoàn voucher như các đường hủy khác
        if (newStatus == OrderStatus.CANCELED) {
            discountUsageService.revertForOrder(order.getOrderId());
        }

        // Điểm thưởng: cộng khi giao xong, trừ lại nếu đơn đã giao bị hủy (idempotent)
        if (newStatus == OrderStatus.DELIVERED) {
            pointService.awardForOrder(order);
        } else if (newStatus == OrderStatus.CANCELED) {
            pointService.revertForOrder(order.getOrderId());
        }

        // Ví shipper: đơn giao xong ⇒ cộng phí ship; đơn CASH (COD) ghi công nợ tiền hàng thu hộ.
        // Idempotent theo orderId; bọc try/catch để lỗi ví không phá luồng cập nhật đơn.
        if (newStatus == OrderStatus.DELIVERED && order.getShipper() != null) {
            try {
                shipperWalletService.settleDelivery(order);
            } catch (Exception e) {
                System.err.println("Lỗi đối soát ví shipper đơn #" + order.getOrderId() + ": " + e.getMessage());
            }
        }

        if ((newStatus == OrderStatus.DELIVERED || newStatus == OrderStatus.CANCELED
                || newStatus == OrderStatus.PAID || newStatus == OrderStatus.CONFIRMED)
                && order.getUser() != null) {
            userTierService.recalcTier(order.getUser().getUserId());
        }

        // Báo cho người mua khi trạng thái thực sự đổi (tránh double-notify)
        if (order.getUser() != null && newStatus != oldStatus) {
            UUID buyerId = order.getUser().getUserId();
            String ref = String.valueOf(order.getOrderId());
            switch (newStatus) {
                case CONFIRMED -> notificationService.notify(buyerId, "ORDER_CONFIRMED",
                        "Đơn hàng #" + ref + " đã xác nhận",
                        "Shop đã xác nhận đơn hàng của bạn.", "ORDER", ref);
                case SHIPPING -> notificationService.notify(buyerId, "ORDER_SHIPPING",
                        "Đơn hàng #" + ref + " đang giao",
                        "Shipper đang trên đường giao hàng cho bạn.", "ORDER", ref);
                case DELIVERED -> notificationService.notify(buyerId, "ORDER_DELIVERED",
                        "Đơn hàng #" + ref + " giao thành công",
                        "Đơn hàng đã được giao thành công. Cảm ơn bạn đã mua sắm!", "ORDER", ref);
                default -> { }
            }
        }

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

        // Công nợ COD âm quá hạn ⇒ chặn nhận đơn mới tới khi nộp lại tiền hàng thu hộ.
        if (shipperWalletService.isBlocked(shipperId)) {
            throw new RuntimeException("Ví đang nợ tiền COD quá hạn — vui lòng nộp lại tiền hàng thu hộ trước khi nhận đơn mới!");
        }

        order.setShipper(shipper);
        order.setStatus(OrderStatus.SHIPPING);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        if (order.getUser() != null) {
            notificationService.notify(order.getUser().getUserId(), "ORDER_CLAIMED",
                    "Đơn hàng #" + orderId + " đã có shipper",
                    "Shipper " + shipper.getName() + " đã nhận giao đơn hàng của bạn.",
                    "ORDER", String.valueOf(orderId));
        }
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
    @Transactional(readOnly = true)
    public ShipperEarningsDTO getShipperEarnings(UUID shipperId) {
        User shipper = userRepository.findById(shipperId)
                .orElseThrow(() -> new RuntimeException("Shipper not found!"));

        LocalDate now = LocalDate.now();
        LocalDate startOfWeek = now.minusDays(now.getDayOfWeek().getValue() - 1L); // thứ 2

        double today = 0, week = 0, month = 0;
        int todayCount = 0, weekCount = 0, monthCount = 0;

        for (Order o : orderRepository.findByShipperOrderByOrderIdDesc(shipper)) {
            if (o.getStatus() != OrderStatus.DELIVERED) continue;
            LocalDateTime ts = o.getPaymentTime() != null ? o.getPaymentTime() : o.getCreatedAt();
            if (ts == null) continue;
            LocalDate d = ts.toLocalDate();
            // Thu nhập = phí gốc; đơn cũ chưa có grossShippingFee thì fallback shippingFee.
            double earn = o.getGrossShippingFee() != null
                    ? o.getGrossShippingFee()
                    : (o.getShippingFee() != null ? o.getShippingFee() : 0.0);

            if (d.isEqual(now)) { today += earn; todayCount++; }
            if (!d.isBefore(startOfWeek)) { week += earn; weekCount++; }
            if (d.getYear() == now.getYear() && d.getMonthValue() == now.getMonthValue()) {
                month += earn; monthCount++;
            }
        }

        return ShipperEarningsDTO.builder()
                .today(today).week(week).month(month)
                .todayCount(todayCount).weekCount(weekCount).monthCount(monthCount)
                .build();
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

        // Đơn COD giao xong được đánh dấu PAID + paymentTime=now ⇒ lọt cửa sổ 24h. Chặn rõ:
        // đơn đã giao không hủy được (chưa có flow trả hàng/hoàn về shop).
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new RuntimeException("Đơn đã giao không thể hủy. Cần flow trả hàng/hoàn tiền riêng.");
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

        // Hoàn voucher đã dùng cho đơn này (un-use + hoàn lượt / hoàn số dư mã hoàn tiền)
        discountUsageService.revertForOrder(orderId);

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

        // Chỉ phát hành mã hoàn tiền cho đơn thanh toán BANK (đã trả tiền thật).
        // CASH (COD) hủy là hủy, không tạo mã khuyến mãi.
        if (isPaidWithinWindow && order.getPaymentMethod() == PaymentMethod.BANK) {
            BigDecimal refundAmount = BigDecimal.valueOf(
                    order.getTotalAmount() != null ? order.getTotalAmount() : 0.0);
            if (refundAmount.signum() > 0) {
                String refundReason = reason != null && !reason.isBlank()
                        ? com.example.FieldFinder.util.CancelReasonLabels.vi(reason)
                        : "Khách hủy đơn trong cửa sổ 24h hoàn tiền";
                // Có TK ngân hàng mặc định ⇒ hoàn TIỀN MẶT (PayOS payout); chưa có ⇒ voucher (cũ)
                bankAccountService.getDefault(order.getUser().getUserId())
                        .ifPresentOrElse(
                                bank -> refundService.issueCashRefund(
                                        order.getUser(), RefundSourceType.ORDER,
                                        String.valueOf(order.getOrderId()), refundAmount, refundReason, bank),
                                () -> refundService.issueRefundCredit(
                                        order.getUser(), RefundSourceType.ORDER,
                                        String.valueOf(order.getOrderId()), refundAmount, refundReason));

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

        userTierService.recalcTier(userId);

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
                .shippingFee(order.getShippingFee())
                .grossShippingFee(order.getGrossShippingFee())
                .status(order.getStatus().name())
                .paymentMethod(order.getPaymentMethod().name())
                .createdAt(order.getCreatedAt())
                .paymentTime(order.getPaymentTime())
                .deliveryAddress(order.getDeliveryAddress())
                .destLat(order.getDestLat())
                .destLng(order.getDestLng())
                .shipperName(order.getShipper() != null ? order.getShipper().getName() : null)
                .shipperId(order.getShipper() != null ? order.getShipper().getUserId().toString() : null)
                .customerId(order.getUser() != null ? order.getUser().getUserId().toString() : null)
                .customerPhone(order.getUser() != null ? order.getUser().getPhone() : null)
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

            // Đơn chưa thanh toán bị auto-hủy: trả voucher về ví user
            discountUsageService.revertForOrder(order.getOrderId());

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