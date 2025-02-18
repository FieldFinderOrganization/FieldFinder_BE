package com.example.FieldFinder.mapper;

import com.example.FieldFinder.dto.UserDto;
import com.example.FieldFinder.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    UserDto toDTO(User user);

    User toEntity(UserDto userDTO);
}
