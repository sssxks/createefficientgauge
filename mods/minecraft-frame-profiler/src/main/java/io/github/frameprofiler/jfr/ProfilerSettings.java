package io.github.frameprofiler.jfr;

/** Immutable, validated settings for one rolling frame-profiling session. */
public record ProfilerSettings(
    boolean enabled,
    int samplePeriodMs,
    int methodThresholdMs,
    int rollingMinutes,
    int snapshotMinutes,
    int maxSizeMiB,
    int maxFiles
) {
    public ProfilerSettings {
        requireRange("samplePeriodMs", samplePeriodMs, 2, 100);
        requireRange("methodThresholdMs", methodThresholdMs, 1, 100);
        requireRange("rollingMinutes", rollingMinutes, 1, 120);
        requireRange("snapshotMinutes", snapshotMinutes, 1, 120);
        requireRange("maxSizeMiB", maxSizeMiB, 16, 1024);
        requireRange("maxFiles", maxFiles, 1, 100);
    }

    public long maxSizeBytes() {
        return maxSizeMiB * 1024L * 1024L;
    }

    private static void requireRange(
        String name,
        int value,
        int minimum,
        int maximum
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be in [" + minimum + ", " + maximum + "]"
            );
        }
    }
}
