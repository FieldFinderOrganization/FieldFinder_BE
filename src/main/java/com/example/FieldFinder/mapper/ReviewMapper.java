package com.example.FieldFinder.mapper;
import com.example.FieldFinder.dto.ReviewDto;
import com.example.FieldFinder.entity.Review;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ReviewMapper {
    ReviewMapper INSTANCE = Mappers.getMapper(ReviewMapper.class);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "pitch.pitchId", target = "pitchId")
    ReviewDto toDTO(Review review);

    @Mapping(source = "userId", target = "user.userId")
    @Mapping(source = "pitchId", target = "pitch.pitchId")
    Review toEntity(ReviewDto reviewDTO);
}
