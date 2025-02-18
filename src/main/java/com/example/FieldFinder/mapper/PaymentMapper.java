package com.example.FieldFinder.mapper;

import com.example.FieldFinder.dto.PaymentDto;
import com.example.FieldFinder.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PaymentMapper {
    PaymentMapper INSTANCE = Mappers.getMapper(PaymentMapper.class);

    @Mapping(source = "booking.bookingId", target = "bookingId")
    PaymentDto toDTO(Payment payment);

    @Mapping(source = "bookingId", target = "booking.bookingId")
    Payment toEntity(PaymentDto paymentDTO);
}
