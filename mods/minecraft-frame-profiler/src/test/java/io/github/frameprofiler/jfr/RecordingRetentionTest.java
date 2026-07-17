package io.github.frameprofiler.jfr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingRetentionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsNewestCompletedFilesAndNeverDeletesActiveSession()
        throws Exception {
        Path oldest = recording("slowframes-snapshot-old.jfr", 1);
        Path newest = recording("slowframes-snapshot-new.jfr", 3);
        Path active = recording("slowframes-session-active.jfr", 0);
        Path unrelated = recording("other-recording.jfr", 0);

        RecordingRetention.prune(temporaryDirectory, active, 1);

        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(active));
        assertTrue(Files.exists(unrelated));
    }

    private Path recording(String name, long seconds) throws Exception {
        Path path = Files.createFile(temporaryDirectory.resolve(name));
        Files.setLastModifiedTime(
            path,
            FileTime.from(Instant.EPOCH.plusSeconds(seconds))
        );
        return path;
    }
}
