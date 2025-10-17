package com.example.FieldFinder.dto.res;


import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrderResponseDTO {
    private Long orderId;
    private String userName;
    private Double totalAmount;
    private String status;
    private String paymentMethod;
    private LocalDateTime createdAt;
    private List<OrderItemResponseDTO> items;
}
