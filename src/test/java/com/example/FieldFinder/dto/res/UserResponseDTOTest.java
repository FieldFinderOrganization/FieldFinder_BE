package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserResponseDTOTest {

    private User buildUserWithDemographics() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setName("Triet");
        user.setEmail("triet@example.com");
        user.setPhone("0901234567");
        user.setRole(User.Role.USER);
        user.setStatus(User.Status.ACTIVE);
        user.setPassword("encoded-pw");
        user.setImageUrl("https://img.example.com/pic.jpg");

        user.setDateOfBirth(LocalDate.of(2000, 5, 15));
        user.setGender(Gender.MALE);
        user.setAddress("123 Nguyen Hue, Q1");
        user.setLatitude(10.7769);
        user.setLongitude(106.7009);
        user.setProvince("Hồ Chí Minh");
        user.setDistrict("Quận 1");
        user.setOccupation("Student");
        user.setPreferredPitchType(Pitch.PitchType.FIVE_A_SIDE);
        user.setPreferredPlayTime(PreferredPlayTime.EVENING);
        return user;
    }

    @Test
    void toDto_mapsAllDemographicFields() {
        User user = buildUserWithDemographics();

        UserResponseDTO dto = UserResponseDTO.toDto(user);

        assertEquals(user.getUserId(), dto.getUserId());
        assertEquals("Triet", dto.getName());
        assertEquals("triet@example.com", dto.getEmail());
        assertEquals("0901234567", dto.getPhone());
        assertEquals(User.Role.USER, dto.getRole());
        assertEquals(User.Status.ACTIVE, dto.getStatus());
        assertEquals("https://img.example.com/pic.jpg", dto.getImageUrl());
        assertTrue(dto.isHasPassword());

        assertEquals(LocalDate.of(2000, 5, 15), dto.getDateOfBirth());
        assertEquals(Gender.MALE, dto.getGender());
        assertEquals("123 Nguyen Hue, Q1", dto.getAddress());
        assertEquals(10.7769, dto.getLatitude());
        assertEquals(106.7009, dto.getLongitude());
        assertEquals("Hồ Chí Minh", dto.getProvince());
        assertEquals("Quận 1", dto.getDistrict());
        assertEquals("Student", dto.getOccupation());
        assertEquals(Pitch.PitchType.FIVE_A_SIDE, dto.getPreferredPitchType());
        assertEquals(PreferredPlayTime.EVENING, dto.getPreferredPlayTime());
    }

    @Test
    void toDto_nullDemographics_mapsToNull() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setName("Triet");
        user.setEmail("triet@example.com");
        user.setRole(User.Role.USER);
        user.setStatus(User.Status.ACTIVE);

        UserResponseDTO dto = UserResponseDTO.toDto(user);

        assertNotNull(dto.getUserId());
        assertEquals("Triet", dto.getName());

        assertNull(dto.getDateOfBirth());
        assertNull(dto.getGender());
        assertNull(dto.getAddress());
        assertNull(dto.getLatitude());
        assertNull(dto.getLongitude());
        assertNull(dto.getProvince());
        assertNull(dto.getDistrict());
        assertNull(dto.getOccupation());
        assertNull(dto.getPreferredPitchType());
        assertNull(dto.getPreferredPlayTime());
    }

    @Test
    void toDto_noPassword_hasPasswordIsFalse() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setName("NoPass");
        user.setEmail("nopass@example.com");
        user.setRole(User.Role.USER);
        user.setStatus(User.Status.ACTIVE);
        user.setPassword(null);

        UserResponseDTO dto = UserResponseDTO.toDto(user);

        assertFalse(dto.isHasPassword());
    }
}
