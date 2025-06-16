package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.service.DiscountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/discounts")
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountService discountService;

    @PostMapping
    public ResponseEntity<DiscountResponseDTO> create(@RequestBody DiscountRequestDTO dto) {
        return ResponseEntity.ok(discountService.createDiscount(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DiscountResponseDTO> update(@PathVariable String id, @RequestBody DiscountRequestDTO dto) {
        return ResponseEntity.ok(discountService.updateDiscount(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        discountService.deleteDiscount(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<DiscountResponseDTO>> getAll() {
        return ResponseEntity.ok(discountService.getAllDiscounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DiscountResponseDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(discountService.getDiscountById(id));
    }
}
