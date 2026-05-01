package com.example.FieldFinder.Enum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreferredPlayTimeTest {

    @Test
    void hasAllExpectedValues() {
        PreferredPlayTime[] values = PreferredPlayTime.values();
        assertEquals(4, values.length);
        assertNotNull(PreferredPlayTime.valueOf("MORNING"));
        assertNotNull(PreferredPlayTime.valueOf("AFTERNOON"));
        assertNotNull(PreferredPlayTime.valueOf("EVENING"));
        assertNotNull(PreferredPlayTime.valueOf("NIGHT"));
    }

    @Test
    void valueOf_invalidValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> PreferredPlayTime.valueOf("MIDNIGHT"));
    }

    @Test
    void ordinal_isStable() {
        assertEquals(0, PreferredPlayTime.MORNING.ordinal());
        assertEquals(1, PreferredPlayTime.AFTERNOON.ordinal());
        assertEquals(2, PreferredPlayTime.EVENING.ordinal());
        assertEquals(3, PreferredPlayTime.NIGHT.ordinal());
    }
}
