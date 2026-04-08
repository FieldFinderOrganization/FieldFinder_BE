package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.entity.User;
import lombok.*;

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
    private String imageUrl;

    public User toEntity(String encodedPassword) {
        return User.builder()
                .name(this.getName())
                .email(this.getEmail())
                .phone(this.getPhone())
                .password(encodedPassword)
                .status(this.getStatus())
                .role(this.getRole())
                .imageUrl(this.getImageUrl())
                .build();
    }

}
