package com.example.FieldFinder.ai.ranking;

import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilarProductRankerTest {

    @Mock
    private CategoryService categoryService;
    @Mock
    private ProductRepository productRepository;

    private SimilarProductRanker ranker;

    private static final Category FOOTBALL_SHOES = Category.builder().categoryId(10L).name("Football Shoes").build();
    private static final Category BASKETBALL_SHOES = Category.builder().categoryId(11L).name("Basketball Shoes").build();

    @BeforeEach
    void setUp() {
        ranker = new SimilarProductRanker(categoryService, productRepository);
    }

    private Product product(long id, Category category, String brand, String sex, int sold) {
        return Product.builder()
                .productId(id)
                .category(category)
                .brand(brand)
                .sex(sex)
                .variants(List.of(ProductVariant.builder().size("42").stockQuantity(10).soldQuantity(sold).build()))
                .build();
    }

    private void stubTypeAndPool(List<Product> pool) {
        when(categoryService.detectProductTypeFromQuery(any(), any(), anyString())).thenReturn("SHOES");
        when(categoryService.expandByProductType("SHOES")).thenReturn(List.of(10L, 11L));
        Page<Product> page = new PageImpl<>(pool);
        when(productRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    }

    @Test
    void tiers_hardGuarantee_andInTierPopularityOrder() {
        Product anchor = product(1L, FOOTBALL_SHOES, "Nike", "Men", 0);

        Product p2 = product(2L, FOOTBALL_SHOES, "Nike", "Men", 5);       // T0
        Product p3 = product(3L, FOOTBALL_SHOES, "Nike", "Unisex", 50);   // T0 (unisex ok), bán chạy hơn p2
        Product p4 = product(4L, FOOTBALL_SHOES, "Adidas", "Men", 100);   // T1 (khác brand)
        Product p5 = product(5L, FOOTBALL_SHOES, "Nike", "Women", 100);   // T1 (khác giới tính)
        Product p6 = product(6L, BASKETBALL_SHOES, "Nike", "Men", 80);    // T2 (khác leaf)
        Product p7 = product(7L, BASKETBALL_SHOES, "Adidas", "Unisex", 10); // T2
        // anchor cũng nằm trong pool trả về -> phải bị loại
        stubTypeAndPool(List.of(anchor, p2, p3, p4, p5, p6, p7));

        List<SimilarProductRanker.TierEntry> out = ranker.rank(anchor, 10);

        // Anchor bị loại
        assertTrue(out.stream().noneMatch(e -> e.productId().equals(1L)));

        // Thứ tự kỳ vọng: T0(p3,p2) -> T1(p4,p5) -> T2(p6,p7)
        assertEquals(List.of(3L, 2L, 4L, 5L, 6L, 7L),
                out.stream().map(SimilarProductRanker.TierEntry::productId).toList());

        // 2 item đầu BẮT BUỘC tier 0 (Nike football, giới tính khớp/unisex)
        assertEquals(0, out.get(0).tier());
        assertEquals(0, out.get(1).tier());
        assertEquals(3L, out.get(0).productId()); // bán chạy hơn lên trước trong tầng

        // p5 (Women) KHÔNG được lọt tier 0
        assertEquals(1, out.stream().filter(e -> e.productId().equals(5L)).findFirst().orElseThrow().tier());
        // p6/p7 (basketball) là tier 2
        assertEquals(2, out.stream().filter(e -> e.productId().equals(6L)).findFirst().orElseThrow().tier());
    }

    @Test
    void degrades_whenNoTier1Candidate() {
        Product anchor = product(1L, FOOTBALL_SHOES, "Nike", "Men", 0);

        Product q2 = product(2L, FOOTBALL_SHOES, "Adidas", "Men", 10);   // T1
        Product q3 = product(3L, FOOTBALL_SHOES, "Nike", "Women", 20);   // T1 (giới tính lệch)
        Product q4 = product(4L, BASKETBALL_SHOES, "Nike", "Men", 30);   // T2
        stubTypeAndPool(List.of(q2, q3, q4));

        List<SimilarProductRanker.TierEntry> out = ranker.rank(anchor, 10);

        // Không có tier 0 -> list mở đầu bằng tier 1, vẫn đủ 3 item
        assertEquals(3, out.size());
        assertTrue(out.stream().noneMatch(e -> e.tier() == 0));
        assertEquals(1, out.get(0).tier());
        assertEquals(3L, out.get(0).productId()); // q3 bán chạy hơn q2 trong tầng
    }

    @Test
    void emptyPool_returnsEmpty() {
        Product anchor = product(1L, FOOTBALL_SHOES, "Nike", "Men", 0);
        stubTypeAndPool(List.of(anchor)); // pool chỉ có anchor -> loại hết

        List<SimilarProductRanker.TierEntry> out = ranker.rank(anchor, 10);

        assertTrue(out.isEmpty());
    }
}
