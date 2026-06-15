package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.ranking.SimilarProductRanker;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.dto.res.SuggestedProductsResponseDTO;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit-test cho lớp ghép tầng gợi ý sản phẩm (nguồn cấp cho image-search & AI chat product card).
 * Trọng tâm: fallback khi ML lỗi, thứ tự CTR rerank, và bất biến tier (tier thấp luôn đứng trước).
 */
@ExtendWith(MockitoExtension.class)
class ProductRecommendationServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ProductService productService;
    @Mock private MLRecommendationService mlService;
    @Mock private SimilarProductRanker similarProductRanker;

    private ProductRecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductRecommendationServiceImpl(
                productRepository, userRepository, orderRepository,
                productService, mlService, similarProductRanker);
        // mongoTemplate giữ null (history-view bị bỏ qua) — đúng nhánh khi không có Mongo.
    }

    private Product product(long id) {
        return Product.builder().productId(id).build();
    }

    private ProductResponseDTO dto(long id) {
        return ProductResponseDTO.builder().id(id).build();
    }

    private List<Long> ids(SuggestedProductsResponseDTO r, java.util.function.Function<SuggestedProductsResponseDTO, List<ProductResponseDTO>> sel) {
        return sel.apply(r).stream().map(ProductResponseDTO::getId).toList();
    }

    @Test
    void productKhongTonTai_traVeRong() {
        when(productRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        SuggestedProductsResponseDTO r = service.getSuggested(99L, null, 10);

        assertTrue(r.getSimilar().isEmpty());
        assertTrue(r.getTopSelling().isEmpty());
        assertTrue(r.getHistoryBased().isEmpty());
    }

    @Test
    void mlLoi_giuThuTuHeuristic() {
        // anchor = 1, similar = [2,3], topSelling = [4,5]
        when(productRepository.findById(1L)).thenReturn(java.util.Optional.of(product(1L)));
        when(similarProductRanker.rank(any(), anyInt())).thenReturn(List.of(
                new SimilarProductRanker.TierEntry(2L, 0),
                new SimilarProductRanker.TierEntry(3L, 0)));
        when(productRepository.findTopSellingProducts(any(Pageable.class)))
                .thenReturn(List.of(product(4L), product(5L)));
        when(productService.getProductsByIds(anyList(), any()))
                .thenReturn(Map.of(2L, dto(2L), 3L, dto(3L), 4L, dto(4L), 5L, dto(5L)));
        // ML ném lỗi → fallback map rỗng → giữ thứ tự gốc.
        when(mlService.rerankCtr(any(), anyList(), anyList(), anyMap()))
                .thenThrow(new RuntimeException("ML down"));

        SuggestedProductsResponseDTO r = service.getSuggested(1L, null, 10);

        assertEquals(List.of(2L, 3L), ids(r, SuggestedProductsResponseDTO::getSimilar));
        assertEquals(List.of(4L, 5L), ids(r, SuggestedProductsResponseDTO::getTopSelling));
    }

    @Test
    void ctrRerank_sapXepTopSellingTheoDiemGiamDan() {
        when(productRepository.findById(1L)).thenReturn(java.util.Optional.of(product(1L)));
        when(similarProductRanker.rank(any(), anyInt())).thenReturn(List.of());
        when(productRepository.findTopSellingProducts(any(Pageable.class)))
                .thenReturn(List.of(product(4L), product(5L), product(6L)));
        when(productService.getProductsByIds(anyList(), any()))
                .thenReturn(Map.of(4L, dto(4L), 5L, dto(5L), 6L, dto(6L)));
        // điểm CTR: 5 cao nhất, rồi 6, rồi 4 → kỳ vọng [5,6,4]
        when(mlService.rerankCtr(any(), anyList(), anyList(), anyMap()))
                .thenReturn(Map.of("4", 0.1, "5", 0.9, "6", 0.5));

        SuggestedProductsResponseDTO r = service.getSuggested(1L, null, 10);

        assertEquals(List.of(5L, 6L, 4L), ids(r, SuggestedProductsResponseDTO::getTopSelling));
    }

    @Test
    void tierThapLuonDungTruoc_duCtrThapHon() {
        // similar: id 2 ở tier 1 (CTR cao), id 3 ở tier 0 (CTR thấp) → tier 0 vẫn phải đứng trước.
        when(productRepository.findById(1L)).thenReturn(java.util.Optional.of(product(1L)));
        when(similarProductRanker.rank(any(), anyInt())).thenReturn(List.of(
                new SimilarProductRanker.TierEntry(2L, 1),
                new SimilarProductRanker.TierEntry(3L, 0)));
        when(productRepository.findTopSellingProducts(any(Pageable.class))).thenReturn(List.of());
        when(productService.getProductsByIds(anyList(), any()))
                .thenReturn(Map.of(2L, dto(2L), 3L, dto(3L)));
        when(mlService.rerankCtr(any(), anyList(), anyList(), anyMap()))
                .thenReturn(Map.of("2", 0.9, "3", 0.1));

        SuggestedProductsResponseDTO r = service.getSuggested(1L, null, 10);

        assertEquals(List.of(3L, 2L), ids(r, SuggestedProductsResponseDTO::getSimilar));
    }

    @Test
    void footballProducts_rongKhiKhongCo() {
        when(productRepository.findFootballProducts(any(Pageable.class))).thenReturn(List.of());

        List<ProductResponseDTO> r = service.getSuggestedFootballProducts(null, 10);

        assertTrue(r.isEmpty());
    }

    @Test
    void footballProducts_ctrRerank() {
        when(productRepository.findFootballProducts(any(Pageable.class)))
                .thenReturn(List.of(product(7L), product(8L)));
        when(productService.getProductsByIds(anyList(), any()))
                .thenReturn(Map.of(7L, dto(7L), 8L, dto(8L)));
        when(mlService.rerankCtr(any(), anyList(), anyList(), anyMap()))
                .thenReturn(Map.of("7", 0.2, "8", 0.8));

        List<ProductResponseDTO> r = service.getSuggestedFootballProducts(null, 10);

        assertEquals(List.of(8L, 7L), r.stream().map(ProductResponseDTO::getId).toList());
    }
}
