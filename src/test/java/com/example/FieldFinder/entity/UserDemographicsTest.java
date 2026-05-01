package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.Enum.PreferredPlayTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserDemographicsTest {

    @Nested
    class BuilderTest {
        @Test
        void builderSetsAllDemographicFields() {
            UUID id = UUID.randomUUID();
            User user = User.builder()
                    .userId(id)
                    .name("Test User")
                    .email("test@example.com")
                    .role(User.Role.USER)
                    .status(User.Status.ACTIVE)
                    .dateOfBirth(LocalDate.of(2000, 6, 15))
                    .gender(Gender.FEMALE)
                    .address("123 Test Street")
                    .latitude(10.80)
                    .longitude(106.65)
                    .province("Hồ Chí Minh")
                    .district("Quận 7")
                    .occupation("Engineer")
                    .preferredPitchType(Pitch.PitchType.ELEVEN_A_SIDE)
                    .preferredPlayTime(PreferredPlayTime.NIGHT)
                    .build();

            assertEquals(id, user.getUserId());
            assertEquals("Test User", user.getName());
            assertEquals(LocalDate.of(2000, 6, 15), user.getDateOfBirth());
            assertEquals(Gender.FEMALE, user.getGender());
            assertEquals("123 Test Street", user.getAddress());
            assertEquals(10.80, user.getLatitude());
            assertEquals(106.65, user.getLongitude());
            assertEquals("Hồ Chí Minh", user.getProvince());
            assertEquals("Quận 7", user.getDistrict());
            assertEquals("Engineer", user.getOccupation());
            assertEquals(Pitch.PitchType.ELEVEN_A_SIDE, user.getPreferredPitchType());
            assertEquals(PreferredPlayTime.NIGHT, user.getPreferredPlayTime());
        }

        @Test
        void builderOmitsDemographics_allNull() {
            User user = User.builder()
                    .name("Minimal")
                    .email("minimal@example.com")
                    .role(User.Role.USER)
                    .build();

            assertEquals("Minimal", user.getName());
            assertNull(user.getDateOfBirth());
            assertNull(user.getGender());
            assertNull(user.getAddress());
            assertNull(user.getLatitude());
            assertNull(user.getLongitude());
            assertNull(user.getProvince());
            assertNull(user.getDistrict());
            assertNull(user.getOccupation());
            assertNull(user.getPreferredPitchType());
            assertNull(user.getPreferredPlayTime());
        }
    }

    @Nested
    class SetterGetterTest {
        @Test
        void setAndGetDemographicFields() {
            User user = new User();

            user.setDateOfBirth(LocalDate.of(1995, 12, 25));
            user.setGender(Gender.OTHER);
            user.setAddress("456 Another Street");
            user.setLatitude(21.03);
            user.setLongitude(105.85);
            user.setProvince("Hà Nội");
            user.setDistrict("Hoàn Kiếm");
            user.setOccupation("Teacher");
            user.setPreferredPitchType(Pitch.PitchType.SEVEN_A_SIDE);
            user.setPreferredPlayTime(PreferredPlayTime.AFTERNOON);

            assertEquals(LocalDate.of(1995, 12, 25), user.getDateOfBirth());
            assertEquals(Gender.OTHER, user.getGender());
            assertEquals("456 Another Street", user.getAddress());
            assertEquals(21.03, user.getLatitude());
            assertEquals(105.85, user.getLongitude());
            assertEquals("Hà Nội", user.getProvince());
            assertEquals("Hoàn Kiếm", user.getDistrict());
            assertEquals("Teacher", user.getOccupation());
            assertEquals(Pitch.PitchType.SEVEN_A_SIDE, user.getPreferredPitchType());
            assertEquals(PreferredPlayTime.AFTERNOON, user.getPreferredPlayTime());
        }

        @Test
        void overwriteDemographicFields() {
            User user = new User();
            user.setGender(Gender.MALE);
            user.setOccupation("Student");

            user.setGender(Gender.UNKNOWN);
            user.setOccupation("Freelancer");

            assertEquals(Gender.UNKNOWN, user.getGender());
            assertEquals("Freelancer", user.getOccupation());
        }

        @Test
        void setDemographicFieldsToNull() {
            User user = new User();
            user.setGender(Gender.MALE);
            user.setLatitude(10.5);
            user.setOccupation("Engineer");

            user.setGender(null);
            user.setLatitude(null);
            user.setOccupation(null);

            assertNull(user.getGender());
            assertNull(user.getLatitude());
            assertNull(user.getOccupation());
        }
    }

    @Nested
    class EdgeCases {
        @Test
        void extremeLatitudeLongitude() {
            User user = new User();
            user.setLatitude(-90.0);
            user.setLongitude(180.0);

            assertEquals(-90.0, user.getLatitude());
            assertEquals(180.0, user.getLongitude());
        }

        @Test
        void futureDateOfBirth_isAccepted() {
            User user = new User();
            LocalDate future = LocalDate.of(2099, 1, 1);
            user.setDateOfBirth(future);

            assertEquals(future, user.getDateOfBirth());
        }

        @Test
        void emptyStringsForTextFields() {
            User user = new User();
            user.setAddress("");
            user.setProvince("");
            user.setDistrict("");
            user.setOccupation("");

            assertEquals("", user.getAddress());
            assertEquals("", user.getProvince());
            assertEquals("", user.getDistrict());
            assertEquals("", user.getOccupation());
        }

        @Test
        void allPitchTypeValues_canBePreferred() {
            User user = new User();

            for (Pitch.PitchType pt : Pitch.PitchType.values()) {
                user.setPreferredPitchType(pt);
                assertEquals(pt, user.getPreferredPitchType());
            }
        }

        @Test
        void allPreferredPlayTimeValues_canBeSet() {
            User user = new User();

            for (PreferredPlayTime ppt : PreferredPlayTime.values()) {
                user.setPreferredPlayTime(ppt);
                assertEquals(ppt, user.getPreferredPlayTime());
            }
        }
    }
}
