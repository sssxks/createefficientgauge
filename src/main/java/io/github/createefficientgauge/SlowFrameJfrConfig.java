package io.github.createefficientgauge;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Configuration for the optional, client-side rolling JFR recording. */
public final class SlowFrameJfrConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SAMPLE_PERIOD_MS;
    public static final ModConfigSpec.IntValue METHOD_THRESHOLD_MS;
    public static final ModConfigSpec.IntValue ROLLING_MINUTES;
    public static final ModConfigSpec.IntValue SNAPSHOT_MINUTES;
    public static final ModConfigSpec.IntValue MAX_SIZE_MIB;
    public static final ModConfigSpec.IntValue MAX_FILES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("slowFrameJfr");

        ENABLED = builder
            .comment(
                "Automatically start a bounded slow-frame JFR recording when the client starts.",
                "Requires Java 25 or newer. It can also be enabled with the JVM argument",
                "-Dcreateefficientgauge.jfr=true without changing this file."
            )
            .define("enabled", false);

        SAMPLE_PERIOD_MS = builder
            .comment("Period for Java and native stack sampling. Lower values have more overhead.")
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
            .comment("How often the rolling window is copied to a JFR file while Minecraft is running.")
            .defineInRange("snapshotMinutes", 10, 1, 120);

        MAX_SIZE_MIB = builder
            .comment("Maximum size of the in-progress rolling JFR repository.")
            .defineInRange("maxSizeMiB", 128, 16, 1024);

        MAX_FILES = builder
            .comment("Maximum number of completed slow-frame JFR files kept in the output directory.")
            .defineInRange("maxFiles", 6, 1, 100);

        builder.pop();
        SPEC = builder.build();
    }

    private SlowFrameJfrConfig() {
    }

    public static boolean enabled() {
        return ENABLED.get() || Boolean.getBoolean("createefficientgauge.jfr");
    }
}
