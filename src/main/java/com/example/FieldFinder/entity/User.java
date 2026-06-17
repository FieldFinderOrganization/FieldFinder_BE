package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.Enum.UserTier;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @Builder.Default
    private Status status = Status.ACTIVE;

    public enum Status {
        ACTIVE, BLOCKED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "Role", nullable = false)
    private Role role;

    public enum Role {
        USER, ADMIN, PROVIDER, SHIPPER
    }

    @Column(name = "ImageUrl", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "LastLoginAt")
    private Date lastLoginAt;

    // Lần hoạt động cuối (WS chat/notification connect & disconnect) — dùng cho presence
    // "Hoạt động X phút trước". Khác lastLoginAt (chỉ set lúc đăng nhập).
    @Column(name = "LastSeenAt")
    private Date lastSeenAt;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "Tier", length = 20)
    @Builder.Default
    private UserTier tier = UserTier.MEMBER;

    @Column(name = "TotalSpent12m")
    private Double totalSpent12m;

    @Column(name = "TierUpdatedAt")
    private LocalDateTime tierUpdatedAt;

    /** Điểm thưởng tích lũy (10.000đ = 1 điểm khi đơn DELIVERED). Row cũ NULL = 0. */
    @Column(name = "Points")
    private Integer points;

    /** Shipper online/sẵn sàng nhận đơn (chỉ dùng cho role SHIPPER). Row cũ NULL = chưa set. */
    @Column(name = "Available")
    private Boolean available;

    /** Loại xe shipper (vd "Xe máy"). Chỉ dùng cho role SHIPPER. */
    @Column(name = "VehicleType")
    private String vehicleType;

    /** Biển số xe shipper. Chỉ dùng cho role SHIPPER. */
    @Column(name = "VehiclePlate")
    private String vehiclePlate;

    /** Row cũ trước khi có cột Tier sẽ NULL — coi như MEMBER. */
    public UserTier getEffectiveTier() {
        return tier != null ? tier : UserTier.MEMBER;
    }

    public int getEffectivePoints() {
        return points != null ? points : 0;
    }
}
