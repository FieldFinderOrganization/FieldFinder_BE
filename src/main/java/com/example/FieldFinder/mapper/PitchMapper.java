package com.example.FieldFinder.mapper;

package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.PitchDTO;
import com.pitchbooking.application.entity.Pitch;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PitchMapper {
    PitchMapper INSTANCE = Mappers.getMapper(PitchMapper.class);

    PitchDTO toDTO(Pitch pitch);

    Pitch toEntity(PitchDTO pitchDTO);
}
