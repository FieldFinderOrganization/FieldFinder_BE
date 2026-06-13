package com.example.FieldFinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class DeliveryFeeService {

    /**
     * @param fee              phí khách trả thực (sau freeship)
     * @param grossFee         phí gốc theo khoảng cách (trước freeship) — để FE hiển thị gạch ngang
     * @param distanceKm       khoảng cách kho -> đích (km)
     * @param etaMinutes       thời gian giao ước tính (phút); null nếu dùng fallback Haversine
     * @param freeshipApplied  có được miễn phí ship không
     * @param freeshipThreshold ngưỡng giá trị đơn để freeship (0 = tắt)
     * @param freeshipMaxKm    bán kính tối đa được freeship (0 = không giới hạn)
     * @param amountToFreeship còn thiếu bao nhiêu để đạt freeship (0 nếu đã đạt / ngoài bán kính / tắt)
     * @param source           "OSRM" | "HAVERSINE"
     */
    public record FeeQuote(double fee,
                           double grossFee,
                           double distanceKm,
                           Long etaMinutes,
                           boolean freeshipApplied,
                           double freeshipThreshold,
                           double freeshipMaxKm,
                           double amountToFreeship,
                           String source) {}

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final RoutingService routingService;
    private final double warehouseLat;
    private final double warehouseLng;

    private final double baseFee;
    private final double perKmFee;
    private final double freeRadiusKm;
    private final double minFee;
    private final double maxFee;
    private final double freeOrderThreshold;
    private final double freeshipMaxKm;
    private final double roadFactor;

    public DeliveryFeeService(RoutingService routingService,
                              @Value("${warehouse.lat}") double warehouseLat,
                              @Value("${warehouse.lng}") double warehouseLng,
                              @Value("${shipping.base-fee:15000}") double baseFee,
                              @Value("${shipping.per-km-fee:5000}") double perKmFee,
                              @Value("${shipping.free-radius-km:2}") double freeRadiusKm,
                              @Value("${shipping.min-fee:15000}") double minFee,
                              @Value("${shipping.max-fee:50000}") double maxFee,
                              @Value("${shipping.free-order-threshold:500000}") double freeOrderThreshold,
                              @Value("${shipping.freeship-max-km:20}") double freeshipMaxKm,
                              @Value("${shipping.road-factor:1.3}") double roadFactor) {
        this.routingService = routingService;
        this.warehouseLat = warehouseLat;
        this.warehouseLng = warehouseLng;
        this.baseFee = baseFee;
        this.perKmFee = perKmFee;
        this.freeRadiusKm = freeRadiusKm;
        this.minFee = minFee;
        this.maxFee = maxFee;
        this.freeOrderThreshold = freeOrderThreshold;
        this.freeshipMaxKm = freeshipMaxKm;
        this.roadFactor = roadFactor;
    }

    /**
     * Tính báo giá phí ship từ kho tới đích.
     *
     * @param destLat            vĩ độ đích
     * @param destLng            kinh độ đích
     * @param amountAfterDiscount giá trị đơn sau giảm giá (để xét ngưỡng freeship)
     */
    public FeeQuote quote(double destLat, double destLng, double amountAfterDiscount) {
        // Khoảng cách + ETA: ưu tiên OSRM (đường bộ thực), fallback Haversine.
        double distanceKm;
        Long etaMinutes;
        String source;

        Optional<RoutingService.Route> route =
                routingService.route(warehouseLat, warehouseLng, destLat, destLng);
        if (route.isPresent()) {
            distanceKm = route.get().distanceMeters() / 1000.0;
            etaMinutes = Math.round(route.get().durationSeconds() / 60.0);
            source = "OSRM";
        } else {
            distanceKm = haversineKm(warehouseLat, warehouseLng, destLat, destLng) * roadFactor;
            etaMinutes = null;
            source = "HAVERSINE";
        }

        // Phí gốc theo khoảng cách.
        double gross = baseFee + perKmFee * Math.max(0, distanceKm - freeRadiusKm);
        gross = Math.max(minFee, Math.min(maxFee, gross));
        gross = roundToThousand(gross);

        boolean freeshipEnabled = freeOrderThreshold > 0;
        boolean inFreeRadius = freeshipMaxKm <= 0 || distanceKm <= freeshipMaxKm;
        boolean freeshipEligible = freeshipEnabled && amountAfterDiscount >= freeOrderThreshold;
        boolean freeshipApplied = freeshipEligible && inFreeRadius;

        double fee = freeshipApplied ? 0.0 : gross;

        // Nudge "còn Yđ để freeship": chỉ gợi ý khi điểm giao nằm trong bán kính free.
        double remaining = freeOrderThreshold - amountAfterDiscount;
        double amountToFreeship =
                (freeshipEnabled && inFreeRadius && remaining > 0) ? remaining : 0.0;

        return new FeeQuote(fee, gross, round1(distanceKm), etaMinutes,
                freeshipApplied, freeOrderThreshold, freeshipMaxKm, amountToFreeship, source);
    }

    private double roundToThousand(double v) {
        return Math.round(v / 1000.0) * 1000.0;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Khoảng cách đường chim bay (km) giữa 2 toạ độ. */
    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
