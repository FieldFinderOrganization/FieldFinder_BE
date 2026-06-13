package com.example.FieldFinder.controller;

import com.example.FieldFinder.service.DeliveryFeeService;
import com.example.FieldFinder.service.DeliveryFeeService.FeeQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Báo giá phí ship cho FE hiển thị preview ở checkout.
 * Số phí thực vẫn được tính lại server-side khi tạo đơn (OrderServiceImpl) — endpoint này
 * chỉ để xem trước, không quyết định giá cuối.
 */
@RestController
@RequestMapping("/api/shipping")
@RequiredArgsConstructor
public class ShippingController {

    private final DeliveryFeeService deliveryFeeService;

    @GetMapping("/quote")
    @PreAuthorize("isAuthenticated()")
    public FeeQuote quote(@RequestParam double destLat,
                          @RequestParam double destLng,
                          @RequestParam(defaultValue = "0") double subtotal) {
        return deliveryFeeService.quote(destLat, destLng, subtotal);
    }
}
