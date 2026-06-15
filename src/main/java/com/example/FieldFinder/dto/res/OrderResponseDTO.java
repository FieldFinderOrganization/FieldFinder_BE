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
    private Double shippingFee;
    private String status;
    private String paymentMethod;
    private LocalDateTime createdAt;
    private LocalDateTime paymentTime;
    private String deliveryAddress;
    private Double destLat;
    private Double destLng;
    private String shipperName;
    private String shipperId;
    private String customerId;
    private String customerPhone;
    private List<OrderItemResponseDTO> items;
}
