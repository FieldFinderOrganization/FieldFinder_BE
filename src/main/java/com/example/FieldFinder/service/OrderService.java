package com.example.FieldFinder.service;



import com.example.FieldFinder.dto.req.OrderRequestDTO;
import com.example.FieldFinder.dto.res.OrderResponseDTO;
import com.example.FieldFinder.dto.res.ShipperEarningsDTO;

import java.util.List;
import java.util.UUID;

public interface OrderService {
    OrderResponseDTO createOrder(OrderRequestDTO request);
    OrderResponseDTO getOrderById(Long id);
    List<OrderResponseDTO> getAllOrders();
    OrderResponseDTO updateOrderStatus(Long id, String status);
    void deleteOrder(Long id);
    OrderResponseDTO cancelOrderByUser(Long id, UUID userId, String reason);

    List<OrderResponseDTO> getOrdersByUserId(UUID userId);

    List<OrderResponseDTO> getAvailableOrdersForShipper();
    OrderResponseDTO claimOrder(Long orderId, UUID shipperId);
    List<OrderResponseDTO> getOrdersByShipperId(UUID shipperId);
    ShipperEarningsDTO getShipperEarnings(UUID shipperId);
}