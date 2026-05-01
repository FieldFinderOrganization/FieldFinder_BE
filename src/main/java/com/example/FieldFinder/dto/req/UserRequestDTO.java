package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import lombok.*;

import java.time.LocalDate;

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

    public User toEntity(String encodedPassword) {
        return User.builder()
                .name(this.getName())
                .email(this.getEmail())
                .phone(this.getPhone())
                .password(encodedPassword)
                .status(this.getStatus())
                .role(this.getRole())
                .imageUrl(this.getImageUrl())
                .dateOfBirth(this.getDateOfBirth())
                .gender(this.getGender())
                .address(this.getAddress())
                .latitude(this.getLatitude())
                .longitude(this.getLongitude())
                .province(this.getProvince())
                .district(this.getDistrict())
                .occupation(this.getOccupation())
                .preferredPitchType(this.getPreferredPitchType())
                .preferredPlayTime(this.getPreferredPlayTime())
                .build();
    }

}
