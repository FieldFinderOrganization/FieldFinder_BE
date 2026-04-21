package com.example.FieldFinder.controller;

import com.example.FieldFinder.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class CloudinaryController {

    private final CloudinaryService cloudinaryService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    // FIX LỖI 403: Dùng hasAuthority để bao quát cả trường hợp có hoặc không có chữ ROLE_
    @PreAuthorize("hasAuthority('ADMIN') or hasAuthority('ROLE_ADMIN') or hasAuthority('PROVIDER') or hasAuthority('ROLE_PROVIDER')")
    public ResponseEntity<?> uploadProductImage(
            @RequestPart("file") MultipartFile file, // Đổi thành @RequestPart để ép chuẩn Multipart
            @RequestParam("categories") List<String> categories) {
        try {
            Map<String, Object> result = cloudinaryService.uploadProductImage(file, categories);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Lỗi xử lý dữ liệu: " + e.getMessage());
        }
    }
}