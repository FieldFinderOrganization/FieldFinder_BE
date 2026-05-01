package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductDiscount;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.service.CloudinaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock ProductRepository productRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock ProductVariantRepository productVariantRepository;
    @Mock DiscountRepository discountRepository;
    @Mock UserDiscountRepository userDiscountRepository;
    @Mock CloudinaryService cloudinaryService;
    @Mock AIChat aiChat;
    @Mock RedisTemplate<String, Object> redisTemplate;

    ProductServiceImpl service;

    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(
                productRepository, categoryRepository, productVariantRepository,
                discountRepository, userDiscountRepository,
                cloudinaryService, aiChat, redisTemplate);

        product = Product.builder()
                .productId(1L)
                .name("Áo bóng đá")
                .price(250000.0)
                .build();

        variant = ProductVariant.builder()
                .id(11L)
                .product(product)
                .size("M")
                .stockQuantity(10)
                .lockedQuantity(0)
                .soldQuantity(0)
                .build();

        lenient().when(redisTemplate.keys(anyString())).thenReturn(null);
    }

    @Nested
    class getProductById {
        @Test
        void hasData_ReturnsResponseDTO() {
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(userDiscountRepository.findUsedDiscountIdsByUserId(any()))
                    .thenReturn(Collections.emptyList());
            when(userDiscountRepository.findWalletByUserId(any()))
                    .thenReturn(Collections.emptyList());

            UUID userId = UUID.randomUUID();
            ProductResponseDTO result = service.getProductById(1L, userId);

            assertNotNull(result);
            assertEquals("Áo bóng đá", result.getName());
        }

        @Test
        void anonymousUser_skipsWalletLookup() {
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));

            ProductResponseDTO result = service.getProductById(1L, null);

            assertNotNull(result);
            verify(userDiscountRepository, never()).findUsedDiscountIdsByUserId(any());
            verify(userDiscountRepository, never()).findWalletByUserId(any());
        }

        @Test
        void notFound_ThrowsException() {
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getProductById(99L, null));
            assertTrue(ex.getMessage().contains("Product not found"));
        }
    }

    @Nested
    class holdStock {
        @Test
        void enoughStock_increasesLockedQuantity() {
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.of(variant));

            service.holdStock(1L, "M", 3);

            assertEquals(3, variant.getLockedQuantity());
            verify(productVariantRepository).save(variant);
        }

        @Test
        void notEnoughStock_ThrowsException() {
            variant.setStockQuantity(2);
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.of(variant));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.holdStock(1L, "M", 5));
            assertTrue(ex.getMessage().contains("hết hàng"));
            verify(productVariantRepository, never()).save(any());
        }

        @Test
        void variantNotFound_ThrowsException() {
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "L"))
                    .thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.holdStock(1L, "L", 1));
            assertTrue(ex.getMessage().contains("Không tìm thấy size"));
        }
    }

    @Nested
    class commitStock {
        @Test
        void existing_decreasesStockAndLocked_increasesSold() {
            variant.setStockQuantity(10);
            variant.setLockedQuantity(3);
            variant.setSoldQuantity(0);
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.of(variant));

            service.commitStock(1L, "M", 2);

            assertEquals(8, variant.getStockQuantity());
            assertEquals(1, variant.getLockedQuantity());
            assertEquals(2, variant.getSoldQuantity());
            verify(productVariantRepository).save(variant);
        }

        @Test
        void variantNotFound_NoOp() {
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "X"))
                    .thenReturn(Optional.empty());

            service.commitStock(1L, "X", 1);

            verify(productVariantRepository, never()).save(any());
        }
    }

    @Nested
    class releaseStock {
        @Test
        void existing_decreasesLockedQuantity() {
            variant.setLockedQuantity(5);
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.of(variant));

            service.releaseStock(1L, "M", 2);

            assertEquals(3, variant.getLockedQuantity());
            verify(productVariantRepository).save(variant);
        }

        @Test
        void existing_locksDoNotGoNegative() {
            variant.setLockedQuantity(1);
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.of(variant));

            service.releaseStock(1L, "M", 5);

            assertEquals(0, variant.getLockedQuantity());
        }

        @Test
        void notFound_singleVariant_FallbackReleases() {
            ProductVariant only = ProductVariant.builder()
                    .product(product).size("Freesize").lockedQuantity(2).build();
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.empty());
            when(productVariantRepository.findAllByProduct_ProductId(1L))
                    .thenReturn(List.of(only));

            service.releaseStock(1L, "M", 1);

            assertEquals(1, only.getLockedQuantity());
            verify(productVariantRepository).save(only);
        }

        @Test
        void notFound_multipleVariants_NoOp() {
            ProductVariant a = ProductVariant.builder().product(product).size("S").lockedQuantity(1).build();
            ProductVariant b = ProductVariant.builder().product(product).size("L").lockedQuantity(1).build();
            when(productVariantRepository.findByProduct_ProductIdAndSize(1L, "M"))
                    .thenReturn(Optional.empty());
            when(productVariantRepository.findAllByProduct_ProductId(1L))
                    .thenReturn(List.of(a, b));

            service.releaseStock(1L, "M", 1);

            verify(productVariantRepository, never()).save(any());
        }
    }

    @Nested
    class applyDiscount {
        @Test
        void notExisting_addsProductDiscount() {
            UUID did = UUID.randomUUID();
            Discount d = Discount.builder().discountId(did).build();
            product.setDiscounts(new ArrayList<>());
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(discountRepository.findById(did)).thenReturn(Optional.of(d));

            service.applyDiscount(1L, did.toString());

            assertEquals(1, product.getDiscounts().size());
            verify(productRepository).save(product);
        }

        @Test
        void alreadyApplied_ThrowsException() {
            UUID did = UUID.randomUUID();
            Discount d = Discount.builder().discountId(did).build();
            ProductDiscount pd = ProductDiscount.builder().product(product).discount(d).build();
            product.setDiscounts(new ArrayList<>(List.of(pd)));

            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(discountRepository.findById(did)).thenReturn(Optional.of(d));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.applyDiscount(1L, did.toString()));
            assertTrue(ex.getMessage().contains("already applied"));
        }

        @Test
        void productNotFound_ThrowsException() {
            UUID did = UUID.randomUUID();
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.applyDiscount(99L, did.toString()));
            assertTrue(ex.getMessage().contains("Product not found"));
        }

        @Test
        void discountNotFound_ThrowsException() {
            UUID did = UUID.randomUUID();
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(discountRepository.findById(did)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.applyDiscount(1L, did.toString()));
            assertTrue(ex.getMessage().contains("Discount not found"));
        }
    }

    @Nested
    class deleteProduct {
        @Test
        void delegatesToRepository() {
            service.deleteProduct(1L);
            verify(productRepository, times(1)).deleteById(1L);
        }
    }
}