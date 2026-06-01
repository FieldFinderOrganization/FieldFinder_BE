package com.example.FieldFinder.dto.req;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProviderAddressRequestDTO {
    private UUID providerId;
    private String address;
    // Toạ độ chính xác từ map picker (nếu có) → bỏ qua geocode chuỗi địa chỉ thô.
    private Double latitude;
    private Double longitude;
}
