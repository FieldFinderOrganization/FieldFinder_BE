package com.example.FieldFinder.dto.req;


import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class OrderRequestDTO {
    private UUID userId;
    private List<OrderItemRequestDTO> items;
    private String paymentMethod;

    private List<String> discountCodes;
}
