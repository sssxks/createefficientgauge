package io.github.createefficientgauge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Starts a bounded JFR recording for correlating slow client frames with Java
 * and native stack samples. This class is referenced only on the physical
 * client so dedicated servers never resolve Minecraft client classes.
 */
public final class SlowFrameJfrProfiler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String RECORDING_NAME = "createefficientgauge-slow-frames";
    private static final String METHOD_TRACE_EVENT = "jdk.MethodTrace";
    private static final String METHOD_FILTER = String.join(";",
        "net.minecraft.client.Minecraft::runTick",
        "com.mojang.blaze3d.platform.Window::updateDisplay",
        "net.minecraft.client.renderer.GameRenderer::render",
        "net.minecraft.client.Minecraft::tick"
    );

    private static Recording recording;
    private static ScheduledExecutorService snapshotExecutor;
    private static Path outputDirectory;
    private static Path sessionDestination;
    private static int maxFiles;

    private SlowFrameJfrProfiler() {
    }

    public static void start() {
        if (!SlowFrameJfrConfig.enabled()) {
            return;
        }

        if (Runtime.version().feature() < 25) {
            LOGGER.warn(
                "Slow-frame JFR profiling was requested, but Java {} is running. "
                    + "Targeted JFR MethodTrace events require Java 25 or newer.",
                Runtime.version().feature()
            );
            return;
        }

        synchronized (LOCK) {
            if (recording != null) {
                return;
            }

            try {
                if (!hasEvent(METHOD_TRACE_EVENT)) {
                    LOGGER.warn("Slow-frame JFR profiling is unavailable: {} is not supported by this JVM.", METHOD_TRACE_EVENT);
                    return;
                }

                outputDirectory = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("debug")
                    .resolve("profiling")
                    .resolve(CreateEfficientGauge.MOD_ID);
                Files.createDirectories(outputDirectory);

                maxFiles = SlowFrameJfrConfig.MAX_FILES.get();
                pruneCompletedFiles(maxFiles - 1);

                String sessionTime = FILE_TIME.format(LocalDateTime.now());
                sessionDestination = outputDirectory.resolve("slowframes-session-" + sessionTime + ".jfr");

                Map<String, String> settings = new HashMap<>(Configuration.getConfiguration("profile").getSettings());
                configureSettings(settings);

                Recording created = new Recording(settings);
                // Store the reference immediately so a failure in any later
                // setup call is still closed by closeAfterFailedStart().
                recording = created;
                created.setName(RECORDING_NAME);
                created.setToDisk(true);
                created.setMaxAge(Duration.ofMinutes(SlowFrameJfrConfig.ROLLING_MINUTES.get()));
                created.setMaxSize(SlowFrameJfrConfig.MAX_SIZE_MIB.get() * 1024L * 1024L);
                created.setDestination(sessionDestination);
                created.setDumpOnExit(true);
                created.start();

                int snapshotMinutes = SlowFrameJfrConfig.SNAPSHOT_MINUTES.get();
                snapshotExecutor = Executors.newSingleThreadScheduledExecutor(new ProfilerThreadFactory());
                snapshotExecutor.scheduleAtFixedRate(
                    SlowFrameJfrProfiler::dumpPeriodicSnapshot,
                    snapshotMinutes,
                    snapshotMinutes,
                    TimeUnit.MINUTES
                );

                Runtime.getRuntime().addShutdownHook(new Thread(
                    SlowFrameJfrProfiler::stopAtShutdown,
                    "Create Efficient Gauge JFR shutdown"
                ));

                LOGGER.info(
                    "Started rolling slow-frame JFR profiling: sample period={} ms, method threshold={} ms, "
                        + "window={} min, max={} MiB, output={}",
                    SlowFrameJfrConfig.SAMPLE_PERIOD_MS.get(),
                    SlowFrameJfrConfig.METHOD_THRESHOLD_MS.get(),
                    SlowFrameJfrConfig.ROLLING_MINUTES.get(),
                    SlowFrameJfrConfig.MAX_SIZE_MIB.get(),
                    outputDirectory
                );
            } catch (Throwable throwable) {
                closeAfterFailedStart();
                LOGGER.error("Could not start slow-frame JFR profiling.", throwable);
            }
        }
    }

    private static boolean hasEvent(String eventName) {
        for (EventType eventType : FlightRecorder.getFlightRecorder().getEventTypes()) {
            if (eventName.equals(eventType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void configureSettings(Map<String, String> settings) {
        int samplePeriod = SlowFrameJfrConfig.SAMPLE_PERIOD_MS.get();
        int methodThreshold = SlowFrameJfrConfig.METHOD_THRESHOLD_MS.get();

        settings.put("jdk.ExecutionSample#enabled", "true");
        settings.put("jdk.ExecutionSample#period", samplePeriod + " ms");
        settings.put("jdk.NativeMethodSample#enabled", "true");
        settings.put("jdk.NativeMethodSample#period", samplePeriod + " ms");

        settings.put(METHOD_TRACE_EVENT + "#enabled", "true");
        settings.put(METHOD_TRACE_EVENT + "#stackTrace", "true");
        settings.put(METHOD_TRACE_EVENT + "#threshold", methodThreshold + " ms");
        settings.put(METHOD_TRACE_EVENT + "#filter", METHOD_FILTER);

        // Allocation profiling is useful for heap investigations but creates
        // substantial noise and data volume for a frame-pacing recording.
        settings.put("jdk.ObjectAllocationSample#enabled", "false");
        settings.put("jdk.ObjectAllocationInNewTLAB#enabled", "false");
        settings.put("jdk.ObjectAllocationOutsideTLAB#enabled", "false");
        settings.put("jdk.OldObjectSample#enabled", "false");
    }

    private static void dumpPeriodicSnapshot() {
        synchronized (LOCK) {
            if (recording == null || recording.getState() != RecordingState.RUNNING) {
                return;
            }

            Path destination = outputDirectory.resolve(
                "slowframes-snapshot-" + FILE_TIME.format(LocalDateTime.now()) + ".jfr"
            );
            try {
                recording.dump(destination);
                pruneCompletedFiles(maxFiles - 1);
                LOGGER.info("Saved rolling slow-frame JFR snapshot to {}", destination);
            } catch (IOException exception) {
                LOGGER.error("Could not save rolling slow-frame JFR snapshot to {}", destination, exception);
            }
        }
    }

    private static void stopAtShutdown() {
        synchronized (LOCK) {
            if (snapshotExecutor != null) {
                snapshotExecutor.shutdownNow();
                snapshotExecutor = null;
            }
            if (recording == null) {
                return;
            }

            try {
                if (recording.getState() == RecordingState.RUNNING) {
                    // Because the recording has a destination, stop() writes
                    // the final rolling window and closes the recording.
                    recording.stop();
                    LOGGER.info("Saved final slow-frame JFR recording to {}", sessionDestination);
                } else {
                    recording.close();
                }
                // sessionDestination is deliberately excluded by the pruning
                // helper, so reserve one of the configured slots for it.
                pruneCompletedFiles(maxFiles - 1);
            } catch (Throwable throwable) {
                LOGGER.error("Could not finish the slow-frame JFR recording cleanly.", throwable);
            } finally {
                recording = null;
            }
        }
    }

    private static void pruneCompletedFiles(int filesToKeep) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return;
        }

        List<Path> completed = new ArrayList<>();
        try (var paths = Files.list(outputDirectory)) {
            paths.filter(path -> path.getFileName().toString().startsWith("slowframes-"))
                .filter(path -> path.getFileName().toString().endsWith(".jfr"))
                .filter(path -> !path.equals(sessionDestination))
                .forEach(completed::add);
        }
        completed.sort(Comparator.comparingLong(SlowFrameJfrProfiler::lastModified).reversed());
        for (int index = Math.max(0, filesToKeep); index < completed.size(); index++) {
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

    private static void closeAfterFailedStart() {
        if (snapshotExecutor != null) {
            snapshotExecutor.shutdownNow();
            snapshotExecutor = null;
        }
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    private static final class ProfilerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "Create Efficient Gauge JFR snapshot");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
