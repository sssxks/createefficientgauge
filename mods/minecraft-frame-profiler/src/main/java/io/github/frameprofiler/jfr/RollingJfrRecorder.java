package io.github.frameprofiler.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;

/**
 * Owns one bounded JFR recording and its periodic snapshot lifecycle.
 *
 * <p>The recorder receives resolved settings and paths from the client boundary;
 * it has no dependency on Minecraft or NeoForge configuration types.</p>
 */
public final class RollingJfrRecorder {

    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String METHOD_TRACE_EVENT = "jdk.MethodTrace";

    private final Logger logger;
    private final Object lock = new Object();

    private Recording recording;
    private ScheduledExecutorService snapshotExecutor;
    private Path outputDirectory;
    private Path sessionDestination;
    private int maxFiles;

    public RollingJfrRecorder(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start(
        Path requestedOutputDirectory,
        ProfilerSettings settings,
        FrameProfile profile
    ) {
        Objects.requireNonNull(requestedOutputDirectory, "outputDirectory");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(profile, "profile");
        if (!settings.enabled()) {
            return;
        }

        if (Runtime.version().feature() < 25) {
            logger.warn(
                "Frame JFR profiling was requested, but Java {} is running. "
                    + "Targeted JFR MethodTrace events require Java 25 or newer.",
                Runtime.version().feature()
            );
            return;
        }

        synchronized (lock) {
            if (recording != null) {
                return;
            }

            try {
                if (!hasEvent(METHOD_TRACE_EVENT)) {
                    logger.warn(
                        "Frame JFR profiling is unavailable: {} is not supported by this JVM.",
                        METHOD_TRACE_EVENT
                    );
                    return;
                }

                outputDirectory = requestedOutputDirectory
                    .toAbsolutePath()
                    .normalize();
                Files.createDirectories(outputDirectory);

                maxFiles = settings.maxFiles();
                String sessionTime = FILE_TIME.format(LocalDateTime.now());
                sessionDestination = outputDirectory.resolve(
                    "slowframes-session-" + sessionTime + ".jfr"
                );
                RecordingRetention.prune(
                    outputDirectory,
                    sessionDestination,
                    maxFiles - 1
                );

                Map<String, String> recordingSettings = new HashMap<>(
                    Configuration.getConfiguration("profile").getSettings()
                );
                configureSettings(recordingSettings, settings, profile);

                Recording created = new Recording(recordingSettings);
                // Store immediately so failures in later setup are still closed.
                recording = created;
                created.setName(profile.recordingName());
                created.setToDisk(true);
                created.setMaxAge(
                    Duration.ofMinutes(settings.rollingMinutes())
                );
                created.setMaxSize(settings.maxSizeBytes());
                created.setDestination(sessionDestination);
                created.setDumpOnExit(true);
                created.start();

                snapshotExecutor = Executors.newSingleThreadScheduledExecutor(
                    new ProfilerThreadFactory()
                );
                snapshotExecutor.scheduleAtFixedRate(
                    this::dumpPeriodicSnapshot,
                    settings.snapshotMinutes(),
                    settings.snapshotMinutes(),
                    TimeUnit.MINUTES
                );

                Runtime.getRuntime().addShutdownHook(new Thread(
                    this::stopAtShutdown,
                    "Minecraft Frame Profiler JFR shutdown"
                ));

                logger.info(
                    "Started rolling frame JFR profiling: sample period={} ms, "
                        + "method threshold={} ms, window={} min, max={} MiB, output={}",
                    settings.samplePeriodMs(),
                    settings.methodThresholdMs(),
                    settings.rollingMinutes(),
                    settings.maxSizeMiB(),
                    outputDirectory
                );
            } catch (Throwable throwable) {
                closeAfterFailedStart();
                logger.error("Could not start frame JFR profiling.", throwable);
            }
        }
    }

    private static boolean hasEvent(String eventName) {
        for (EventType eventType : FlightRecorder
            .getFlightRecorder()
            .getEventTypes()) {
            if (eventName.equals(eventType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void configureSettings(
        Map<String, String> recordingSettings,
        ProfilerSettings settings,
        FrameProfile profile
    ) {
        String samplePeriod = settings.samplePeriodMs() + " ms";
        recordingSettings.put("jdk.ExecutionSample#enabled", "true");
        recordingSettings.put("jdk.ExecutionSample#period", samplePeriod);
        recordingSettings.put("jdk.NativeMethodSample#enabled", "true");
        recordingSettings.put("jdk.NativeMethodSample#period", samplePeriod);

        recordingSettings.put(METHOD_TRACE_EVENT + "#enabled", "true");
        recordingSettings.put(METHOD_TRACE_EVENT + "#stackTrace", "true");
        recordingSettings.put(
            METHOD_TRACE_EVENT + "#threshold",
            settings.methodThresholdMs() + " ms"
        );
        recordingSettings.put(
            METHOD_TRACE_EVENT + "#filter",
            profile.methodFilter()
        );

        // Allocation profiling adds volume without helping frame pacing.
        recordingSettings.put(
            "jdk.ObjectAllocationSample#enabled",
            "false"
        );
        recordingSettings.put(
            "jdk.ObjectAllocationInNewTLAB#enabled",
            "false"
        );
        recordingSettings.put(
            "jdk.ObjectAllocationOutsideTLAB#enabled",
            "false"
        );
        recordingSettings.put("jdk.OldObjectSample#enabled", "false");
    }

    private void dumpPeriodicSnapshot() {
        synchronized (lock) {
            if (
                recording == null
                || recording.getState() != RecordingState.RUNNING
            ) {
                return;
            }

            Path destination = outputDirectory.resolve(
                "slowframes-snapshot-"
                    + FILE_TIME.format(LocalDateTime.now())
                    + ".jfr"
            );
            try {
                recording.dump(destination);
                RecordingRetention.prune(
                    outputDirectory,
                    sessionDestination,
                    maxFiles - 1
                );
                logger.info(
                    "Saved rolling frame JFR snapshot to {}",
                    destination
                );
            } catch (IOException exception) {
                logger.error(
                    "Could not save rolling frame JFR snapshot to {}",
                    destination,
                    exception
                );
            }
        }
    }

    private void stopAtShutdown() {
        synchronized (lock) {
            if (snapshotExecutor != null) {
                snapshotExecutor.shutdownNow();
                snapshotExecutor = null;
            }
            if (recording == null) {
                return;
            }

            Recording active = recording;
            try {
                if (active.getState() == RecordingState.RUNNING) {
                    active.stop();
                    logger.info(
                        "Saved final frame JFR recording to {}",
                        sessionDestination
                    );
                }
                RecordingRetention.prune(
                    outputDirectory,
                    sessionDestination,
                    maxFiles - 1
                );
            } catch (Throwable throwable) {
                logger.error(
                    "Could not finish the frame JFR recording cleanly.",
                    throwable
                );
            } finally {
                active.close();
                recording = null;
            }
        }
    }

    private void closeAfterFailedStart() {
        if (snapshotExecutor != null) {
            snapshotExecutor.shutdownNow();
            snapshotExecutor = null;
        }
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    private static final class ProfilerThreadFactory
        implements ThreadFactory {

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                task,
                "Minecraft Frame Profiler JFR snapshot"
            );
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
