package com.example.FieldFinder.mapper;
package com.pitchbooking.application.mapper;

import com.pitchbooking.application.dto.UserDTO;
import com.pitchbooking.application.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    UserDTO toDTO(User user);

    User toEntity(UserDTO userDTO);
}
