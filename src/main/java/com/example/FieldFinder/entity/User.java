package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "Users")
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "UserId", updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "Name", nullable = false)
    private String name;

    @Column(name = "Email", nullable = false, unique = true)
    private String email;

    @Column(name = "Phone", nullable = true)
    private String phone;

    @Column(name = "Password", nullable = true)
    private String password;

    @Column(name = "Status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    public enum Status {
        ACTIVE, BLOCKED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "Role", nullable = false)
    private Role role;

    public enum Role {
        USER, ADMIN, PROVIDER
    }

    @Column(name = "ImageUrl", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "LastLoginAt")
    private Date lastLoginAt;

    @Column(name = "CreatedAt", updatable = false)
    private Date createdAt;

    @Column(name = "DateOfBirth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "Gender")
    private Gender gender;

    @Column(name = "Address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "Latitude")
    private Double latitude;

    @Column(name = "Longitude")
    private Double longitude;

    @Column(name = "Province")
    private String province;

    @Column(name = "District")
    private String district;

    @Column(name = "Occupation")
    private String occupation;

    @Enumerated(EnumType.STRING)
    @Column(name = "PreferredPitchType")
    private Pitch.PitchType preferredPitchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "PreferredPlayTime")
    private PreferredPlayTime preferredPlayTime;
}
