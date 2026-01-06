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

    public User toEntity(String firebaseUid, String encodedPassword) {
        return User.builder()
                .name(this.getName())
                .email(this.getEmail())
                .phone(this.getPhone())
                .password(encodedPassword)
                .status(this.getStatus())
                .role(this.getRole())
                .firebaseUid(firebaseUid)
                .build();
    }

}
