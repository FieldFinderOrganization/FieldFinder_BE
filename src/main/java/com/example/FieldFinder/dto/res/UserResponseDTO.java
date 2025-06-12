package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.User;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {
    private UUID userId;
    private String name;
    private String email;
    private String phone;
    private User.Role role;
    private User.Status status;

    // Chuyển từ Entity sang DTO
    public static UserResponseDTO toDto(User user) {
        return new UserResponseDTO(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus()
        );
    }
}
