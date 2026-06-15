package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.OrderItemRequestDTO;
import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.DeliveryFeeService;
import com.example.FieldFinder.service.DiscountUsageService;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.PointService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.service.UserTierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderConcurrencyTest {

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
    @Mock UserTierService userTierService;
    @Mock DiscountUsageService discountUsageService;
    @Mock PointService pointService;
    @Mock NotificationService notificationService;
    @Mock DeliveryFeeService deliveryFeeService;

    @InjectMocks OrderServiceImpl service;

    private static final Long PRODUCT_ID = 1L;
    private static final String SIZE = "M";

    private UUID userId;

    /** Tồn khả dụng còn lại — nguồn sự thật chung cho mọi thread. */
    private final AtomicInteger availableStock = new AtomicInteger(1);
    private final AtomicLong orderIdSeq = new AtomicLong(0);
    /** 1 RLock thật cho mỗi key (productId+size), chia sẻ giữa các thread. */
    private final Map<String, RLock> lockByKey = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        User user = new User();
        user.setUserId(userId);
        user.setName("Buyer");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId(orderIdSeq.incrementAndGet());
            return o;
        });
        when(orderItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Redisson lock giả lập bằng ReentrantLock thật theo key -> mutual exclusion thực sự.
        when(redissonClient.getLock(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return lockByKey.computeIfAbsent(key, k -> buildRLock(new ReentrantLock()));
        });

        // Trừ tồn KHÔNG atomic + ngủ ngắn: nếu thiếu lock sẽ oversell. Có lock -> chỉ 1 thắng.
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setName("Demo Shoe");
        when(stockLockService.lockStock(eq(PRODUCT_ID), eq(SIZE), anyInt())).thenAnswer(inv -> {
            int qty = inv.getArgument(2);
            int avail = availableStock.get();   // đọc
            Thread.sleep(15);                   // mở rộng cửa sổ race
            if (avail < qty) {
                throw new RuntimeException("Rất tiếc! Sản phẩm vừa hết hàng hoặc không đủ số lượng.");
            }
            availableStock.set(avail - qty);    // ghi
            return new StockLockService.LockResult(product, 100000.0 * qty);
        });
    }

    /** Tạo RLock mock ủy quyền cho 1 ReentrantLock thật (chỉ những method createOrder dùng). */
    private RLock buildRLock(ReentrantLock real) {
        RLock rlock = mock(RLock.class);
        try {
            when(rlock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(inv -> real.tryLock(inv.getArgument(0), inv.getArgument(2)));
        } catch (InterruptedException ignored) {
            // chữ ký có throws; lambda không thực sự ném ở bước stub
        }
        when(rlock.isHeldByCurrentThread()).thenAnswer(inv -> real.isHeldByCurrentThread());
        doAnswer(inv -> { real.unlock(); return null; }).when(rlock).unlock();
        return rlock;
    }

    private OrderRequestDTO buyOneRequest() {
        OrderItemRequestDTO item = new OrderItemRequestDTO();
        item.setProductId(PRODUCT_ID);
        item.setSize(SIZE);
        item.setQuantity(1);

        OrderRequestDTO req = new OrderRequestDTO();
        req.setUserId(userId);
        req.setPaymentMethod("BANK");   // tránh nhánh CASH (commit tồn + tính hạng)
        req.setItems(List.of(item));
        return req;
    }

    @Test
    void no_oversell_when_many_threads_buy_last_unit() throws InterruptedException {
        int threads = 20;
        availableStock.set(1);          // tồn chỉ còn 1

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                // createOrder đăng ký TransactionSynchronization -> cần synchronization active.
                TransactionSynchronizationManager.initSynchronization();
                try {
                    start.await();
                    service.createOrder(buyOneRequest());
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf(
                "[DAT SP] %d thread mua unit cuoi (ton ban dau=1) -> %d don thanh cong / %d truot | ton con=%d%n",
                threads, success.get(), failure.get(), availableStock.get());

        assertThat(finished).as("tất cả thread phải kết thúc").isTrue();
        assertThat(success.get()).as("chỉ 1 đơn mua được unit cuối").isEqualTo(1);
        assertThat(failure.get()).as("N-1 đơn còn lại bị từ chối").isEqualTo(threads - 1);
        assertThat(availableStock.get()).as("tồn không âm, không bán vượt").isZero();
    }

    @Test
    void exactly_stock_count_orders_succeed() throws InterruptedException {
        int threads = 20;
        int stock = 5;
        availableStock.set(stock);      // tồn = 5 -> đúng 5 đơn thắng

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    start.await();
                    service.createOrder(buyOneRequest());
                    success.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf(
                "[DAT SP] %d thread, ton ban dau=%d -> %d don thanh cong | ton con=%d (khong oversell)%n",
                threads, stock, success.get(), availableStock.get());

        assertThat(finished).isTrue();
        assertThat(success.get()).as("đúng bằng số tồn").isEqualTo(stock);
        assertThat(availableStock.get()).as("bán hết sạch tồn, không vượt").isZero();
    }
}
