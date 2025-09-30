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
    private User.Status status;

    // Chuyển từ DTO sang Entity
    public User toEntity(String firebaseUid, String encodedPassword) {
        return User.builder()
                // KHÔNG set userId ở đây, Hibernate sẽ tự generate
                .name(this.getName())
                .email(this.getEmail())
                .phone(this.getPhone())
                .password(encodedPassword) // có thể null nếu dùng Firebase password
                .status(this.getStatus())
                .role(this.getRole())
                .firebaseUid(firebaseUid)
                .build();
    }

}
