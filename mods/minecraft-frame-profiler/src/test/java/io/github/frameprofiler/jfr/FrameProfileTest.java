package io.github.frameprofiler.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrameProfileTest {

    @Test
    void rendersTheExactJfrMethodFilter() {
        FrameProfile profile = new FrameProfile(
            "test",
            List.of(
                new FrameProfile.MethodTarget("example.First", "one"),
                new FrameProfile.MethodTarget("example.Second", "two")
            )
        );

        assertEquals(
            "example.First::one;example.Second::two",
            profile.methodFilter()
        );
    }

    @Test
    void requiresAtLeastOneMethodTarget() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new FrameProfile("test", List.of())
        );
    }
}
