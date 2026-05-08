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

@Service
@RequiredArgsConstructor
public class StockLockService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    public record LockResult(Product product, double price) {}

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

        int oldLocked = variant.getLockedQuantity();
        variant.setLockedQuantity(oldLocked + quantity);
        productVariantRepository.save(variant);
        System.out.println(String.format(
                "[lockStock] productId=%d size=%s qty=%d | locked %d→%d (REQUIRES_NEW commit)",
                productId, size, quantity, oldLocked, oldLocked + quantity));

        double price = product.getEffectivePrice() * quantity;
        return new LockResult(product, price);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public LockResult lockStockWithDbLock(Long productId, String size, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

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
