package io.github.frameprofiler.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProfilerSettingsTest {

    @Test
    void convertsMaximumSizeToBytesWithoutIntegerOverflow() {
        ProfilerSettings settings = new ProfilerSettings(
            true,
            5,
            10,
            10,
            10,
            1024,
            6
        );

        assertEquals(1_073_741_824L, settings.maxSizeBytes());
    }

    @Test
    void rejectsValuesOutsideTheConfigContract() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProfilerSettings(true, 1, 10, 10, 10, 128, 6)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProfilerSettings(true, 5, 10, 10, 10, 128, 0)
        );
    }
}
