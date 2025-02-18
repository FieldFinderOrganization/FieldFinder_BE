package com.example.FieldFinder.dto;


import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PitchDto {
    private UUID pitchId;
    private String name;
    private String address;
    private String type;
    private Double pricePerHour;
    private String imageUrl;
}
