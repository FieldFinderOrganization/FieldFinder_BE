package com.example.FieldFinder.mapper;

import com.example.FieldFinder.dto.PromotionDto;
import com.example.FieldFinder.entity.Promotion;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PromotionMapper {
    PromotionMapper INSTANCE = Mappers.getMapper(PromotionMapper.class);

    PromotionDto toDTO(Promotion promotion);

    Promotion toEntity(PromotionDto promotionDTO);
}
