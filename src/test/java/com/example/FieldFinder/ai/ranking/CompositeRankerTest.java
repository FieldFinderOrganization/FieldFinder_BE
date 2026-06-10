package com.example.FieldFinder.ai.ranking;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Thứ tự ưu tiên CỐ ĐỊNH khi user nêu thuộc tính trong query:
 * brand → giới tính → màu → size (vd "giày nike nam màu đen size 39").
 */
@ExtendWith(MockitoExtension.class)
class CompositeRankerTest {

    @Mock
    private CategoryService categoryService;

    private CompositeRanker ranker;

    @BeforeEach
    void setUp() {
        ranker = new CompositeRanker(categoryService);
        // Mọi candidate đều coi là đúng type — test chỉ quan tâm thứ tự trong tier 1.
        lenient().when(categoryService.productMatchesType(any(), any())).thenReturn(true);
    }

    private ProductResponseDTO shoe(long id, String brand, String sex, String color,
                                    String size, int qty) {
        return ProductResponseDTO.builder()
                .id(id)
                .name("Shoe " + id)
                .brand(brand)
                .sex(sex)
                .dominantColor(color)
                .categoryName("Running Shoes")
                .price(1_000_000.0)
                .variants(List.of(ProductResponseDTO.VariantDTO.builder()
                        .size(size).quantity(qty).stockTotal(qty).build()))
                .build();
    }

    private List<Long> rankIds(List<ProductResponseDTO> candidates, RankingContext ctx) {
        List<Double> ml = new ArrayList<>(Collections.nCopies(candidates.size(), 0.5));
        return ranker.rank(candidates, ml, ctx).stream()
                .map(e -> e.getKey().getId())
                .toList();
    }

    private RankingContext.RankingContextBuilder baseCtx() {
        return RankingContext.builder()
                .productType("SHOES")
                .topBrands(List.of())
                .activityCats(Collections.emptySet())
                .strictProductType(true);
    }

    @Test
    void fullQuery_BrandThenGenderThenColorThenSize() {
        // Query: "giày nike nam màu đen size 39"
        ProductResponseDTO exact = shoe(1, "Nike", "MEN", "đen", "39", 5);       // khớp đủ
        ProductResponseDTO noSize = shoe(2, "Nike", "MEN", "đen", "40", 5);      // hết 39
        ProductResponseDTO wrongColor = shoe(3, "Nike", "MEN", "trắng", "39", 5);// sai màu
        ProductResponseDTO unisex = shoe(4, "Nike", "UNISEX", "đen", "39", 5);   // unisex
        ProductResponseDTO wrongBrand = shoe(5, "Adidas", "MEN", "đen", "39", 5);// sai brand

        RankingContext ctx = baseCtx()
                .queryBrand("Nike").queryGender("MEN").queryColor("đen").querySize("39")
                .build();

        List<Long> ids = rankIds(List.of(wrongBrand, unisex, wrongColor, noSize, exact), ctx);
        // brand thắng tất → Adidas cuối; trong Nike: đúng giới (2) > unisex (1);
        // trong Nike-MEN: màu đúng > màu sai; trong Nike-MEN-đen: còn size > hết size.
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids);
    }

    @Test
    void genderBeatsColor() {
        // "giày nam màu đen": sai giới + đúng màu phải xếp SAU đúng giới + sai màu.
        ProductResponseDTO menWhite = shoe(1, "Nike", "MEN", "trắng", "40", 5);
        ProductResponseDTO womenBlack = shoe(2, "Nike", "WOMEN", "đen", "40", 5);

        RankingContext ctx = baseCtx().queryGender("MEN").queryColor("đen").build();

        assertEquals(List.of(1L, 2L), rankIds(List.of(womenBlack, menWhite), ctx));
    }

    @Test
    void colorBeatsSize() {
        // "giày màu đen size 39": sai màu + còn size xếp SAU đúng màu + hết size.
        ProductResponseDTO blackNoSize = shoe(1, "Nike", "MEN", "đen", "40", 5);
        ProductResponseDTO whiteWithSize = shoe(2, "Nike", "MEN", "trắng", "39", 5);

        RankingContext ctx = baseCtx().queryColor("đen").querySize("39").build();

        assertEquals(List.of(1L, 2L), rankIds(List.of(whiteWithSize, blackNoSize), ctx));
    }

    @Test
    void sizeOutOfStockDoesNotMatch() {
        // Variant size 39 nhưng quantity 0 → coi như hết size, xếp sau sp còn 39.
        ProductResponseDTO inStock = shoe(1, "Nike", "MEN", "đen", "39", 3);
        ProductResponseDTO outOfStock = shoe(2, "Nike", "MEN", "đen", "39", 0);

        RankingContext ctx = baseCtx().querySize("39").build();

        assertEquals(List.of(1L, 2L), rankIds(List.of(outOfStock, inStock), ctx));
    }

    @Test
    void unisexScoresBetweenExactAndWrongGender() {
        ProductResponseDTO men = shoe(1, "Nike", "MEN", "đen", "40", 5);
        ProductResponseDTO uni = shoe(2, "Nike", "UNISEX", "đen", "40", 5);
        ProductResponseDTO women = shoe(3, "Nike", "WOMEN", "đen", "40", 5);

        RankingContext ctx = baseCtx().queryGender("MEN").build();

        assertEquals(List.of(1L, 2L, 3L), rankIds(List.of(women, uni, men), ctx));
    }

    @Test
    void noStatedAttributes_NewKeysAreNoop() {
        // Không nêu giới/size → queryGenderScore = 0 và querySizeMatch = false cho mọi sp,
        // thứ tự quyết bởi các tín hiệu cũ (ở đây: composite/ml giữ nguyên thứ tự vào).
        ProductResponseDTO a = shoe(1, "Nike", "MEN", "đen", "39", 5);
        ProductResponseDTO b = shoe(2, "Adidas", "WOMEN", "trắng", "40", 0);

        RankingContext ctx = baseCtx().build();

        List<Map.Entry<ProductResponseDTO, Double>> ranked =
                ranker.rank(List.of(a, b), List.of(0.9, 0.1), ctx);
        assertEquals(2, ranked.size());
        assertEquals(1L, ranked.get(0).getKey().getId()); // ml cao hơn → đứng đầu, không bị key mới xáo
    }

    @Test
    void targetSizeMaxValue_ReturnsBeyondDefaultTen() {
        // Catalog nhỏ vẫn >10 sp cùng brand: mẫu brand khác còn size phải LỌT kết quả
        // khi targetSize mở rộng (tiered grouping cần thấy nó), dù rank sau 12 sp Nike.
        List<ProductResponseDTO> pool = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            pool.add(shoe(i, "Nike", "MEN", "đen", "40", 5)); // Nike hết 39
        }
        ProductResponseDTO adidasWith39 = shoe(99, "Adidas", "MEN", "đen", "39", 5);
        pool.add(adidasWith39);

        RankingContext deep = baseCtx()
                .queryBrand("Nike").querySize("39")
                .targetSize(Integer.MAX_VALUE)
                .build();
        List<Long> ids = rankIds(pool, deep);
        assertEquals(13, ids.size());
        assertEquals(99L, ids.get(12)); // Adidas cuối (sai brand) nhưng CÓ MẶT

        RankingContext capped = baseCtx().queryBrand("Nike").querySize("39").build();
        assertEquals(10, rankIds(pool, capped).size()); // default 10 giữ nguyên
    }

    @Test
    void staticHelpers() {
        ProductResponseDTO p = shoe(1, "Nike", "MEN", "đen", "39", 2);
        assertTrue(CompositeRanker.hasSizeInStock(p, "39"));
        assertTrue(CompositeRanker.hasSizeInStock(p, " 39 "));   // trim
        assertFalse(CompositeRanker.hasSizeInStock(p, "40"));
        assertFalse(CompositeRanker.hasSizeInStock(p, null));
        assertFalse(CompositeRanker.hasSizeInStock(shoe(2, "Nike", "MEN", "đen", "39", 0), "39"));

        assertEquals(2, CompositeRanker.queryGenderScore(p, "MEN"));
        assertEquals(0, CompositeRanker.queryGenderScore(p, "WOMEN"));
        assertEquals(1, CompositeRanker.queryGenderScore(
                shoe(3, "Nike", "UNISEX", "đen", "39", 1), "MEN"));
        assertEquals(0, CompositeRanker.queryGenderScore(p, null));
    }
}
