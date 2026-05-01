package com.example.FieldFinder.Enum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenderTest {

    @Test
    void hasAllExpectedValues() {
        Gender[] values = Gender.values();
        assertEquals(4, values.length);
        assertNotNull(Gender.valueOf("MALE"));
        assertNotNull(Gender.valueOf("FEMALE"));
        assertNotNull(Gender.valueOf("OTHER"));
        assertNotNull(Gender.valueOf("UNKNOWN"));
    }

    @Test
    void valueOf_invalidValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> Gender.valueOf("NONBINARY"));
    }

    @Test
    void ordinal_isStable() {
        assertEquals(0, Gender.MALE.ordinal());
        assertEquals(1, Gender.FEMALE.ordinal());
        assertEquals(2, Gender.OTHER.ordinal());
        assertEquals(3, Gender.UNKNOWN.ordinal());
    }
}
