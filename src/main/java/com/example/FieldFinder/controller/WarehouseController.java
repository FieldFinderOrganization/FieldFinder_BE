package com.example.FieldFinder.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {

    @Value("${warehouse.lat}")
    private double lat;
    @Value("${warehouse.lng}")
    private double lng;
    @Value("${warehouse.name}")
    private String name;
    @Value("${warehouse.address}")
    private String address;

    @GetMapping
    public Map<String, Object> warehouse() {
        return Map.of("lat", lat, "lng", lng, "name", name, "address", address);
    }
}
