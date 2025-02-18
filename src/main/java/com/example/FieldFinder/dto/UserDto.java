package com.example.FieldFinder.dto;


import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private UUID userId;
    private String username;
    private String email;
    private String phoneNumber;
    private String role;
}
