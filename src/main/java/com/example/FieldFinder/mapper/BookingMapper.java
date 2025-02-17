package com.example.FieldFinder.mapper;

package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.BookingDTO;
import com.pitchbooking.application.entity.Booking;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface BookingMapper {
    BookingMapper INSTANCE = Mappers.getMapper(BookingMapper.class);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "pitch.pitchId", target = "pitchId")
    BookingDTO toDTO(Booking booking);

    @Mapping(source = "userId", target = "user.userId")
    @Mapping(source = "pitchId", target = "pitch.pitchId")
    Booking toEntity(BookingDTO bookingDTO);
}
