package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tách riêng việc lock/unlock tồn kho ra một bean độc lập để Spring proxy
 * có thể tạo transaction REQUIRES_NEW thực sự — tức là commit ngay khi method
 * return, trước khi Redis lock được giải phóng ở bên ngoài.
 *
 * Nếu đặt method này trong cùng bean với createOrder thì @Transactional sẽ
 * không tạo transaction mới (self-invocation bypass proxy), dẫn đến cùng bug cũ.
 */
@Service
@RequiredArgsConstructor
public class StockLockService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    public record LockResult(Product product, double price) {}

    /**
     * Kiểm tra tồn kho, tăng lockedQuantity và commit ngay lập tức.
     * Gọi method này trong khi đang giữ Redis lock để đảm bảo:
     *   Redis lock released AFTER DB commit (không phải trước).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public LockResult lockStock(Long productId, String size, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        ProductVariant variant = productVariantRepository
                .findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy size " + size + " cho sản phẩm " + product.getName()));

        if (variant.getAvailableQuantity() < quantity) {
            throw new RuntimeException("Rất tiếc! Sản phẩm " + product.getName()
                    + " (Size " + size + ") vừa hết hàng hoặc không đủ số lượng.");
        }

        variant.setLockedQuantity(variant.getLockedQuantity() + quantity);
        productVariantRepository.save(variant);
        // Transaction REQUIRES_NEW commit ở đây, trước khi method return

        double price = product.getEffectivePrice() * quantity;
        return new LockResult(product, price);
    }

    /**
     * Fallback khi Redis down: dùng SELECT FOR UPDATE thay cho Redis lock.
     * DB row lock được giữ cho đến khi REQUIRES_NEW transaction commit,
     * đảm bảo không có race condition dù không có Redis.
     *
     * Đánh đổi: user có thể chờ lâu hơn nếu nhiều người mua cùng lúc
     * (DB block thay vì fast-fail như Redis), nhưng hệ thống vẫn đúng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public LockResult lockStockWithDbLock(Long productId, String size, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        // SELECT FOR UPDATE — block các thread khác tại DB level cho đến khi commit
        ProductVariant variant = productVariantRepository
                .findByProductIdAndSizeForUpdate(productId, size)
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy size " + size + " cho sản phẩm " + product.getName()));

        if (variant.getAvailableQuantity() < quantity) {
            throw new RuntimeException("Rất tiếc! Sản phẩm " + product.getName()
                    + " (Size " + size + ") vừa hết hàng hoặc không đủ số lượng.");
        }

        variant.setLockedQuantity(variant.getLockedQuantity() + quantity);
        productVariantRepository.save(variant);

        double price = product.getEffectivePrice() * quantity;
        return new LockResult(product, price);
    }

    /**
     * Giảm lockedQuantity để hoàn tác khi createOrder bị lỗi giữa chừng
     * (compensation cho các item đã lock thành công trước đó).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true)
    })
    public void unlockStock(Long productId, String size, int quantity) {
        productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .ifPresent(variant -> {
                    variant.setLockedQuantity(
                            Math.max(0, variant.getLockedQuantity() - quantity));
                    productVariantRepository.save(variant);
                });
    }
}
