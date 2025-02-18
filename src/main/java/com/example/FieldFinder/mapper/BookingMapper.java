package com.example.FieldFinder.mapper;

import com.example.FieldFinder.dto.BookingDto;
import com.example.FieldFinder.entity.Booking;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface BookingMapper {
    BookingMapper INSTANCE = Mappers.getMapper(BookingMapper.class);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "pitch.pitchId", target = "pitchId")
    BookingDto toDTO(Booking booking);

    @Mapping(source = "userId", target = "user.userId")
    @Mapping(source = "pitchId", target = "pitch.pitchId")
    Booking toEntity(BookingDto bookingDTO);
}
