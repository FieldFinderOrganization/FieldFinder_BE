package com.example.FieldFinder.service;



import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;

import java.util.List;
import java.util.UUID;

public interface OrderService {
    OrderResponseDTO createOrder(OrderRequestDTO request);
    OrderResponseDTO getOrderById(Long id);
    List<OrderResponseDTO> getAllOrders();
    OrderResponseDTO updateOrderStatus(Long id, String status);
    void deleteOrder(Long id);

    List<OrderResponseDTO> getOrdersByUserId(UUID userId);
}
