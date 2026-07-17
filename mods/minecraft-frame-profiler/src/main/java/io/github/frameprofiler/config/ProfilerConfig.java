package io.github.frameprofiler.config;

import io.github.frameprofiler.jfr.ProfilerSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

/** NeoForge client configuration boundary for frame profiling. */
public final class ProfilerConfig {

    public static final String SYSTEM_PROPERTY =
        "minecraftframeprofiler.enabled";
    public static final String LEGACY_SYSTEM_PROPERTY =
        "createefficientgauge.jfr";

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue SAMPLE_PERIOD_MS;
    private static final ModConfigSpec.IntValue METHOD_THRESHOLD_MS;
    private static final ModConfigSpec.IntValue ROLLING_MINUTES;
    private static final ModConfigSpec.IntValue SNAPSHOT_MINUTES;
    private static final ModConfigSpec.IntValue MAX_SIZE_MIB;
    private static final ModConfigSpec.IntValue MAX_FILES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("recording");

        ENABLED = builder
            .comment(
                "Automatically start a bounded frame JFR recording when the client starts.",
                "Requires Java 25 or newer. It can also be enabled with the JVM argument",
                "-D" + SYSTEM_PROPERTY + "=true without changing this file."
            )
            .define("enabled", false);

        SAMPLE_PERIOD_MS = builder
            .comment(
                "Period for Java and native stack sampling. Lower values have more overhead."
            )
            .defineInRange("samplePeriodMs", 5, 2, 100);

        METHOD_THRESHOLD_MS = builder
            .comment(
                "Only frame-phase method calls at least this long are recorded.",
                "10 ms retains useful phase boundaries without recording every fast frame."
            )
            .defineInRange("methodThresholdMs", 10, 1, 100);

        ROLLING_MINUTES = builder
            .comment("How many recent minutes each rolling recording retains.")
            .defineInRange("rollingMinutes", 10, 1, 120);

        SNAPSHOT_MINUTES = builder
            .comment(
                "How often the rolling window is copied to a JFR file while Minecraft is running."
            )
            .defineInRange("snapshotMinutes", 10, 1, 120);

        MAX_SIZE_MIB = builder
            .comment("Maximum size of the in-progress rolling JFR repository.")
            .defineInRange("maxSizeMiB", 128, 16, 1024);

        MAX_FILES = builder
            .comment(
                "Maximum number of completed frame JFR files kept in the output directory."
            )
            .defineInRange("maxFiles", 6, 1, 100);

        builder.pop();
        SPEC = builder.build();
    }

    private ProfilerConfig() {}

    /** Captures all startup settings so the recorder has no NeoForge dependency. */
    public static ProfilerSettings snapshot() {
        return new ProfilerSettings(
            ENABLED.get()
                || Boolean.getBoolean(SYSTEM_PROPERTY)
                || legacySystemPropertyEnabled(),
            SAMPLE_PERIOD_MS.get(),
            METHOD_THRESHOLD_MS.get(),
            ROLLING_MINUTES.get(),
            SNAPSHOT_MINUTES.get(),
            MAX_SIZE_MIB.get(),
            MAX_FILES.get()
        );
    }

    public static boolean legacySystemPropertyEnabled() {
        return Boolean.getBoolean(LEGACY_SYSTEM_PROPERTY);
    }
}
