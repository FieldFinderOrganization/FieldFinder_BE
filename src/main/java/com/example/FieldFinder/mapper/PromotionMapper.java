package com.example.FieldFinder.mapper;

package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.PromotionDTO;
import com.pitchbooking.application.entity.Promotion;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PromotionMapper {
    PromotionMapper INSTANCE = Mappers.getMapper(PromotionMapper.class);

    PromotionDTO toDTO(Promotion promotion);

    Promotion toEntity(PromotionDTO promotionDTO);
}
