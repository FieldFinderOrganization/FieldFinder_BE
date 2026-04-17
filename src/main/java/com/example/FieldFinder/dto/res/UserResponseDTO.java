package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.User;
import lombok.*;

import java.util.Date;
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
    private String imageUrl;
    private boolean hasPassword;
    private Date lastLoginAt;

    public static UserResponseDTO toDto(User user) {
        return new UserResponseDTO(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getImageUrl(),
                user.getPassword() != null,
                user.getLastLoginAt()
        );
    }
}
