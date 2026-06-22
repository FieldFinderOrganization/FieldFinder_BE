package com.example.FieldFinder.ai.util;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiTextUtilTest {

    @Test
    void buildPriceRangeMessage_under_over_between() {
        assertEquals("dưới 500,000 VNĐ", AiTextUtil.buildPriceRangeMessage(0.0, 500000.0));
        assertEquals("trên 1,000,000 VNĐ", AiTextUtil.buildPriceRangeMessage(1000000.0, Double.MAX_VALUE));
        assertEquals("từ 200,000 đến 500,000 VNĐ", AiTextUtil.buildPriceRangeMessage(200000.0, 500000.0));
    }

    @Test
    void effectivePrice_saleWhenDiscountedElsePrice() {
        ProductResponseDTO onSale = ProductResponseDTO.builder().price(300000.0).salePercent(10).salePrice(270000.0).build();
        ProductResponseDTO noSale = ProductResponseDTO.builder().price(300000.0).build();
        assertEquals(270000.0, AiTextUtil.effectivePrice(onSale));
        assertEquals(300000.0, AiTextUtil.effectivePrice(noSale));
    }

    @Test
    void buildCriteriaDesc_composesTypeBrandGenderColor() {
        assertEquals("giày Nike nam màu đen",
                AiTextUtil.buildCriteriaDesc("SHOES", "Nike", "nam", "đen"));
        assertEquals("sản phẩm", AiTextUtil.buildCriteriaDesc(null, null, null, null));
    }

    @Test
    void findPitchByPrice_cheapestAndDearest() {
        PitchResponseDTO a = new PitchResponseDTO(); a.setPrice(java.math.BigDecimal.valueOf(100000));
        PitchResponseDTO b = new PitchResponseDTO(); b.setPrice(java.math.BigDecimal.valueOf(300000));
        assertEquals(0, AiTextUtil.findPitchByPrice(List.of(a, b), true).getPrice().compareTo(java.math.BigDecimal.valueOf(100000)));
        assertEquals(0, AiTextUtil.findPitchByPrice(List.of(a, b), false).getPrice().compareTo(java.math.BigDecimal.valueOf(300000)));
        assertNull(AiTextUtil.findPitchByPrice(List.of(), true));
    }

    @Test
    void detectEnvironmentFromInput_indoorOutdoorNull() {
        assertEquals(PitchEnvironment.OUTDOOR, AiTextUtil.detectEnvironmentFromInput("sân ngoài trời"));
        assertEquals(PitchEnvironment.INDOOR, AiTextUtil.detectEnvironmentFromInput("sân có mái che"));
        assertNull(AiTextUtil.detectEnvironmentFromInput("sân 5 người"));
        assertNull(AiTextUtil.detectEnvironmentFromInput(null));
    }

    @Test
    void formatMoney_groupsThousandsNoDecimals() {
        assertEquals("250,000", AiTextUtil.formatMoney(250000.0));
        assertEquals("0", AiTextUtil.formatMoney(0.0));
        assertEquals("1,000,000", AiTextUtil.formatMoney(1000000.4));
    }

    @Test
    void capitalize_firstCharUpper_handlesEmptyAndNull() {
        assertEquals("Nike", AiTextUtil.capitalize("nike"));
        assertEquals("", AiTextUtil.capitalize(""));
        assertNull(AiTextUtil.capitalize(null));
    }

    @Test
    void productTypeLabel_mapsKnown_nullForUnknown() {
        assertEquals("giày", AiTextUtil.productTypeLabel("SHOES"));
        assertEquals("áo", AiTextUtil.productTypeLabel("top")); // case-insensitive
        assertNull(AiTextUtil.productTypeLabel("UNKNOWN"));
        assertNull(AiTextUtil.productTypeLabel(null));
    }

    @Test
    void translateCategory_prefixMatchAndExact() {
        assertEquals("giày đá bóng", AiTextUtil.translateCategory("Football Shoes"));
        assertEquals("quần đùi", AiTextUtil.translateCategory("Shorts"));
        assertEquals("phụ kiện tennis", AiTextUtil.translateCategory("Tennis Accessories"));
        assertEquals("", AiTextUtil.translateCategory(null));
        // không khớp → trả nguyên gốc
        assertEquals("Random", AiTextUtil.translateCategory("Random"));
    }

    @Test
    void formatEnvironment_indoorOutdoorElseEmpty() {
        assertEquals("trong nhà", AiTextUtil.formatEnvironment(PitchEnvironment.INDOOR));
        assertEquals("ngoài trời", AiTextUtil.formatEnvironment(PitchEnvironment.OUTDOOR));
    }

    @Test
    void formatPitchType_mapsSizes_elsePassthrough() {
        assertEquals("sân 5", AiTextUtil.formatPitchType("FIVE_A_SIDE"));
        assertEquals("sân 7", AiTextUtil.formatPitchType("SEVEN_A_SIDE"));
        assertEquals("sân 11", AiTextUtil.formatPitchType("ELEVEN_A_SIDE"));
        assertEquals("XXX", AiTextUtil.formatPitchType("XXX"));
    }

    @Test
    void isGreeting_detectsViAndEn_rejectsOther() {
        assertTrue(AiTextUtil.isGreeting("hello"));
        assertTrue(AiTextUtil.isGreeting("Xin chào"));
        assertTrue(AiTextUtil.isGreeting("chào shop"));
        assertFalse(AiTextUtil.isGreeting("tôi muốn mua giày"));
    }

    @Test
    void isAffordableListQuery_distinguishesListFromExtreme() {
        assertTrue(AiTextUtil.isAffordableListQuery("mắc quá cho tôi mấy đôi rẻ rẻ thôi"));
        assertTrue(AiTextUtil.isAffordableListQuery("cho tôi option giá rẻ hơn"));
        assertFalse(AiTextUtil.isAffordableListQuery("giày rẻ nhất"));
        assertFalse(AiTextUtil.isAffordableListQuery("sản phẩm mắc nhất"));
        assertFalse(AiTextUtil.isAffordableListQuery(null));
    }
}
