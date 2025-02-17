package com.example.FieldFinder.mapper;

package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.PaymentDTO;
import com.pitchbooking.application.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PaymentMapper {
    PaymentMapper INSTANCE = Mappers.getMapper(PaymentMapper.class);

    @Mapping(source = "booking.bookingId", target = "bookingId")
    PaymentDTO toDTO(Payment payment);

    @Mapping(source = "bookingId", target = "booking.bookingId")
    Payment toEntity(PaymentDTO paymentDTO);
}
