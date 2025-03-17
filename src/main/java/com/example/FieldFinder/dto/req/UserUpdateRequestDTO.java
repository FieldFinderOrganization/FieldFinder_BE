package com.example.FieldFinder.dto.req;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter

public class UserUpdateRequestDTO {
    private String name;
    private String email;
    private String phone;
}
