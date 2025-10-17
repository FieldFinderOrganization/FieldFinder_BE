package com.example.FieldFinder.service;



import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;

import java.util.List;

public interface OrderService {
    OrderResponseDTO createOrder(OrderRequestDTO request);
    OrderResponseDTO getOrderById(Long id);
    List<OrderResponseDTO> getAllOrders();
    OrderResponseDTO updateOrderStatus(Long id, String status);
    void deleteOrder(Long id);
}
