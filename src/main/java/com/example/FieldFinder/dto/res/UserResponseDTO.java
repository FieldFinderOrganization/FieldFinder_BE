package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import lombok.*;

import java.time.LocalDate;
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
                user.getLastLoginAt(),
                user.getDateOfBirth(),
                user.getGender(),
                user.getAddress(),
                user.getLatitude(),
                user.getLongitude(),
                user.getProvince(),
                user.getDistrict(),
                user.getOccupation(),
                user.getPreferredPitchType(),
                user.getPreferredPlayTime()
        );
    }
}
