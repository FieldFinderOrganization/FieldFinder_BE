package com.example.FieldFinder.dto.req;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class UserRequestDTOTest {

    @Test
    void toEntity_mapsAllDemographicFields() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Triet");
        dto.setEmail("triet@example.com");
        dto.setPhone("0901234567");
        dto.setRole(User.Role.USER);
        dto.setStatus(User.Status.ACTIVE);
        dto.setImageUrl("https://img.example.com/pic.jpg");

        dto.setDateOfBirth(LocalDate.of(2000, 5, 15));
        dto.setGender(Gender.FEMALE);
        dto.setAddress("456 Le Loi, Q3");
        dto.setLatitude(10.78);
        dto.setLongitude(106.69);
        dto.setProvince("Hồ Chí Minh");
        dto.setDistrict("Quận 3");
        dto.setOccupation("Office Worker");
        dto.setPreferredPitchType(Pitch.PitchType.SEVEN_A_SIDE);
        dto.setPreferredPlayTime(PreferredPlayTime.MORNING);

        User entity = dto.toEntity("encoded-password");

        assertEquals("Triet", entity.getName());
        assertEquals("triet@example.com", entity.getEmail());
        assertEquals("0901234567", entity.getPhone());
        assertEquals("encoded-password", entity.getPassword());
        assertEquals(User.Role.USER, entity.getRole());
        assertEquals(User.Status.ACTIVE, entity.getStatus());
        assertEquals("https://img.example.com/pic.jpg", entity.getImageUrl());

        assertEquals(LocalDate.of(2000, 5, 15), entity.getDateOfBirth());
        assertEquals(Gender.FEMALE, entity.getGender());
        assertEquals("456 Le Loi, Q3", entity.getAddress());
        assertEquals(10.78, entity.getLatitude());
        assertEquals(106.69, entity.getLongitude());
        assertEquals("Hồ Chí Minh", entity.getProvince());
        assertEquals("Quận 3", entity.getDistrict());
        assertEquals("Office Worker", entity.getOccupation());
        assertEquals(Pitch.PitchType.SEVEN_A_SIDE, entity.getPreferredPitchType());
        assertEquals(PreferredPlayTime.MORNING, entity.getPreferredPlayTime());
    }

    @Test
    void toEntity_nullDemographics_allNull() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Triet");
        dto.setEmail("triet@example.com");
        dto.setRole(User.Role.USER);
        dto.setStatus(User.Status.ACTIVE);

        User entity = dto.toEntity("encoded-password");

        assertEquals("Triet", entity.getName());
        assertNull(entity.getDateOfBirth());
        assertNull(entity.getGender());
        assertNull(entity.getAddress());
        assertNull(entity.getLatitude());
        assertNull(entity.getLongitude());
        assertNull(entity.getProvince());
        assertNull(entity.getDistrict());
        assertNull(entity.getOccupation());
        assertNull(entity.getPreferredPitchType());
        assertNull(entity.getPreferredPlayTime());
    }

    @Test
    void toEntity_partialDemographics_onlySetFieldsMapped() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Partial");
        dto.setEmail("partial@example.com");
        dto.setRole(User.Role.USER);
        dto.setStatus(User.Status.ACTIVE);

        dto.setGender(Gender.OTHER);
        dto.setProvince("Hà Nội");

        User entity = dto.toEntity("pw");

        assertEquals(Gender.OTHER, entity.getGender());
        assertEquals("Hà Nội", entity.getProvince());
        assertNull(entity.getDateOfBirth());
        assertNull(entity.getLatitude());
        assertNull(entity.getOccupation());
    }
}
