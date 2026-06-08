package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Pitch.PitchType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class PitchResponseDTO {
    private UUID pitchId;
    private UUID providerAddressId;
    private String name;
    private PitchType type;
    private PitchEnvironment environment;
    private BigDecimal price;
    private String description;
    private List<String> imageUrls;
    private String address;
    private Double latitude;
    private Double longitude;
    private String providerUserId;
    private String providerName;

    public static PitchResponseDTO fromEntity(Pitch pitch) {
        PitchResponseDTO dto = new PitchResponseDTO();
        dto.setPitchId(pitch.getPitchId());
        dto.setProviderAddressId(pitch.getProviderAddress().getProviderAddressId());
        dto.setName(pitch.getName());
        dto.setType(pitch.getType());
        dto.setEnvironment(pitch.getEnvironment());
        dto.setPrice(pitch.getPrice());
        dto.setDescription(pitch.getDescription());
        dto.setImageUrls(pitch.getImageUrls());
        var pa = pitch.getProviderAddress();
        dto.setAddress(pa.getAddress());
        // Toạ độ riêng của sân; fallback toạ độ khu vực khi sân chưa được nạp (data cũ).
        dto.setLatitude(pitch.getLatitude() != null ? pitch.getLatitude() : pa.getLatitude());
        dto.setLongitude(pitch.getLongitude() != null ? pitch.getLongitude() : pa.getLongitude());
        var providerUser = pa.getProvider().getUser();
        dto.setProviderUserId(providerUser.getUserId().toString());
        dto.setProviderName(providerUser.getName());
        return dto;
    }
}
