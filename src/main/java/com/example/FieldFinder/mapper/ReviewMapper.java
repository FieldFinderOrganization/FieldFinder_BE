package com.example.FieldFinder.mapper;

package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.ReviewDTO;
import com.pitchbooking.application.entity.Review;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ReviewMapper {
    ReviewMapper INSTANCE = Mappers.getMapper(ReviewMapper.class);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "pitch.pitchId", target = "pitchId")
    ReviewDTO toDTO(Review review);

    @Mapping(source = "userId", target = "user.userId")
    @Mapping(source = "pitchId", target = "pitch.pitchId")
    Review toEntity(ReviewDTO reviewDTO);
}
