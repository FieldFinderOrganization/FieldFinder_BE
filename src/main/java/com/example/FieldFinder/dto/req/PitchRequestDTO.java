package com.example.FieldFinder.dto.req;


import com.example.FieldFinder.entity.Pitch.PitchType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PitchRequestDTO {
    private UUID ownerId;
    private String name;
    private String address;
    private PitchType type;
    private BigDecimal price;
    private String imageUrl;
    private String description;
}
