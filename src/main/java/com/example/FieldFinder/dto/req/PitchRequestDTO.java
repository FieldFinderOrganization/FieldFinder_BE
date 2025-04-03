package com.example.FieldFinder.dto.req;


import com.example.FieldFinder.entity.Pitch.PitchType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PitchRequestDTO {
    private UUID providerAddressId;
    private String name;
    private PitchType type;
    private BigDecimal price;
    private String description;
}
