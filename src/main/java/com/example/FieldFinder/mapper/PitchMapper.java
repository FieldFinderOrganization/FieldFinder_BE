package com.example.FieldFinder.mapper;

import com.example.FieldFinder.dto.PitchDto;
import com.example.FieldFinder.entity.Pitch;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PitchMapper {
    PitchMapper INSTANCE = Mappers.getMapper(PitchMapper.class);

    PitchDto toDTO(Pitch pitch);

    Pitch toEntity(PitchDto pitchDTO);
}
