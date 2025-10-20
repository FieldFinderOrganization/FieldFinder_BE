package com.example.FieldFinder.dto.res;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartResponseDTO {
    private Long cartId;
    private UUID userId;
    private String userName;
    private LocalDateTime createdAt;
}
