package io.github.frameprofiler.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Retention policy for completed frame-profiler recordings. */
final class RecordingRetention {

    private RecordingRetention() {}

    static void prune(
        Path outputDirectory,
        Path activeSession,
        int filesToKeep
    ) throws IOException {
        if (filesToKeep < 0) {
            throw new IllegalArgumentException("filesToKeep must not be negative");
        }
        if (!Files.isDirectory(outputDirectory)) {
            return;
        }

        List<Path> completed = new ArrayList<>();
        try (var paths = Files.list(outputDirectory)) {
            paths
                .filter(path -> path
                    .getFileName()
                    .toString()
                    .startsWith("slowframes-"))
                .filter(path -> path
                    .getFileName()
                    .toString()
                    .endsWith(".jfr"))
                .filter(path -> !path.equals(activeSession))
                .forEach(completed::add);
        }
        completed.sort(
            Comparator.comparingLong(RecordingRetention::lastModified)
                .reversed()
        );
        for (int index = filesToKeep; index < completed.size(); index++) {
            Files.deleteIfExists(completed.get(index));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }
}
