package com.example.FieldFinder.service;

import com.example.FieldFinder.service.DeliveryFeeService.FeeQuote;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryFeeServiceTest {

    private static final double WH_LAT = 10.0;
    private static final double WH_LNG = 106.0;

    private DeliveryFeeService service(RoutingService routing) {
        return new DeliveryFeeService(routing, WH_LAT, WH_LNG,
                15000, 5000, 2, 15000, 50000, 500000, 20, 1.3);
    }

    /** OSRM trả khoảng cách km cho trước (eta 10 phút mặc định). */
    private RoutingService routingKm(double km) {
        RoutingService routing = mock(RoutingService.class);
        when(routing.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new RoutingService.Route("", km * 1000.0, 600.0)));
        return routing;
    }

    @Test
    void nearWithinFreeRadius_clampsToMinFee() {
        FeeQuote q = service(routingKm(1.0)).quote(10.0, 106.0, 100_000);
        assertThat(q.source()).isEqualTo("OSRM");
        assertThat(q.fee()).isEqualTo(15000);          // base+0, clamp min
        assertThat(q.freeshipApplied()).isFalse();
    }

    @Test
    void midDistance_basePlusPerKm() {
        FeeQuote q = service(routingKm(5.0)).quote(10.0, 106.0, 100_000);
        // 15000 + 5000*(5-2) = 30000
        assertThat(q.fee()).isEqualTo(30000);
    }

    @Test
    void farDistance_clampsToMaxFee() {
        FeeQuote q = service(routingKm(50.0)).quote(10.0, 106.0, 100_000);
        assertThat(q.fee()).isEqualTo(50000);          // clamp max
    }

    @Test
    void overThreshold_withinRadius_isFree() {
        FeeQuote q = service(routingKm(5.0)).quote(10.0, 106.0, 600_000);
        assertThat(q.freeshipApplied()).isTrue();
        assertThat(q.fee()).isZero();
        assertThat(q.grossFee()).isEqualTo(30000);     // vẫn báo phí gốc để FE gạch ngang
        assertThat(q.amountToFreeship()).isZero();
    }

    @Test
    void overThreshold_beyondRadius_stillCharged() {
        FeeQuote q = service(routingKm(30.0)).quote(10.0, 106.0, 600_000);
        assertThat(q.freeshipApplied()).isFalse();
        assertThat(q.fee()).isEqualTo(50000);          // 15000+5000*28 clamp max
        assertThat(q.amountToFreeship()).isZero();     // ngoài bán kính -> không nudge
    }

    @Test
    void underThreshold_withinRadius_returnsAmountToFreeship() {
        FeeQuote q = service(routingKm(5.0)).quote(10.0, 106.0, 350_000);
        assertThat(q.freeshipApplied()).isFalse();
        assertThat(q.fee()).isEqualTo(30000);
        assertThat(q.amountToFreeship()).isEqualTo(150_000);  // 500k - 350k
    }

    @Test
    void osrmDown_fallsBackToHaversine() {
        RoutingService routing = mock(RoutingService.class);
        when(routing.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
        // Đích trùng kho -> haversine 0 -> phí = min, eta null, source HAVERSINE
        FeeQuote q = service(routing).quote(WH_LAT, WH_LNG, 100_000);
        assertThat(q.source()).isEqualTo("HAVERSINE");
        assertThat(q.etaMinutes()).isNull();
        assertThat(q.distanceKm()).isZero();
        assertThat(q.fee()).isEqualTo(15000);
    }
}
