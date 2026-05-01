package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class UserUpdateRequestDTO {
    private String name;
    private String email;
    private String phone;
    private User.Status status;
    private String imageUrl;

    private LocalDate dateOfBirth;
    private Gender gender;
    private String address;
    private Double latitude;
    private Double longitude;
    private String province;
    private String district;
    private String occupation;
    private Pitch.PitchType preferredPitchType;
    private PreferredPlayTime preferredPlayTime;
}
