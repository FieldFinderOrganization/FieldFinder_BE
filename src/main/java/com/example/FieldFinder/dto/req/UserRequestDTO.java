package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.entity.User;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserRequestDTO {
    private String name;
    private String email;
    private String phone;
    private String password;
    private User.Role role;

    // Chuyển từ DTO sang Entity
    public User toEntity(UUID userId, String encodedPassword) {
        return new User(
                userId,
                this.name,
                this.email,
                this.phone,
                encodedPassword, // Mã hóa trước khi lưu vào DB
                this.role
        );
    }
}
