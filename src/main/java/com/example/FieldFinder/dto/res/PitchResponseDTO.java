package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Pitch.PitchType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PitchResponseDTO {
    private UUID pitchId;
    private UUID providerAddressId;
    private String name;
    private PitchType type;
    private BigDecimal price;
    private String description;
    public static PitchResponseDTO fromEntity(Pitch pitch) {
        PitchResponseDTO dto = new PitchResponseDTO();
        dto.setPitchId(pitch.getPitchId());
        dto.setProviderAddressId(pitch.getProviderAddress().getProviderAddressId());
        dto.setName(pitch.getName());
        dto.setType(pitch.getType());
        dto.setPrice(pitch.getPrice());
        dto.setDescription(pitch.getDescription());
        return dto;
    }
}
