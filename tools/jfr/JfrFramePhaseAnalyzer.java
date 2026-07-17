import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Prints slow Minecraft frames, their phases, and correlated render-thread samples. */
public final class JfrFramePhaseAnalyzer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());

    private record Trace(String owner, String method, Instant start, Instant end, double ms) {
        boolean is(String expectedOwner, String expectedMethod) {
            return owner.equals(expectedOwner) && method.equals(expectedMethod);
        }
    }

    private record Frame(Trace total, double renderMs, double displayMs, double tickMs) {
        double otherMs() {
            return Math.max(0.0, total.ms - renderMs - displayMs - tickMs);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java JfrFramePhaseAnalyzer.java <recording.jfr>");
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(Path.of(args[0]));
        List<Trace> traces = readMethodTraces(events);
        boolean hasFrames = traces.stream()
            .anyMatch(trace -> trace.is("net.minecraft.client.Minecraft", "runTick"));
        if (!hasFrames) {
            throw new IllegalArgumentException(
                "No Minecraft.runTick MethodTrace events found. Was this produced by the slow-frame configuration?"
            );
        }

        // Starting MethodTrace can briefly retransform and recompile its target
        // classes. Do not report that one-time disturbance as a gameplay spike.
        Instant recordingStart = events.stream()
            .map(RecordedEvent::getStartTime)
            .min(Comparator.naturalOrder())
            .orElseThrow();
        Instant usableStart = recordingStart.plusSeconds(2);
        List<Trace> phases = traces.stream()
            .filter(trace -> !trace.is("net.minecraft.client.Minecraft", "runTick"))
            .toList();
        List<Frame> frames = buildFrames(traces, phases, usableStart);
        frames.sort(Comparator.comparingDouble((Frame frame) -> frame.total.ms).reversed());

        System.out.printf("Recording: %s%n", args[0]);
        System.out.printf("Traced frames after 2 s instrumentation warm-up: %d%n", frames.size());
        if (!frames.isEmpty()) {
            double[] durations = frames.stream().mapToDouble(frame -> frame.total.ms).sorted().toArray();
            System.out.printf(
                "Traced runTick duration ms: median=%.2f p90=%.2f p99=%.2f max=%.2f%n%n",
                percentile(durations, 0.50),
                percentile(durations, 0.90),
                percentile(durations, 0.99),
                durations[durations.length - 1]
            );
        }

        System.out.println("Longest traced frames (phase calls below the configured threshold are included in other):");
        System.out.println("time             total   render  display    tick   other");
        frames.stream().limit(30).forEach(frame -> System.out.printf(
            "%s  %7.2f  %7.2f  %7.2f %7.2f %7.2f%n",
            TIME.format(frame.total.start),
            frame.total.ms,
            frame.renderMs,
            frame.displayMs,
            frame.tickMs,
            frame.otherMs()
        ));

        long displayStalls = frames.stream().filter(frame -> frame.displayMs >= 10.0).count();
        long renderStalls = frames.stream().filter(frame -> frame.renderMs >= 20.0 && frame.displayMs < 10.0).count();
        long tickStalls = frames.stream().filter(frame -> frame.tickMs >= 10.0).count();
        System.out.printf(
            "%nClassification counts: updateDisplay >=10 ms: %d; render >=20 ms without display stall: %d; tick >=10 ms: %d%n",
            displayStalls,
            renderStalls,
            tickStalls
        );

        Frame longest = frames.stream().findFirst().orElse(null);
        printOutlier(events, "longest frame", longest);

        Frame cpuOutlier = frames.stream()
            .filter(frame -> frame.displayMs < 10.0)
            .max(Comparator.comparingDouble(Frame::renderMs))
            .orElse(null);
        if (cpuOutlier != longest) {
            printOutlier(events, "largest CPU-render outlier", cpuOutlier);
        }

        Frame tickOutlier = frames.stream().max(Comparator.comparingDouble(Frame::tickMs)).orElse(null);
        if (tickOutlier != longest && tickOutlier != cpuOutlier && tickOutlier != null && tickOutlier.tickMs >= 10.0) {
            printOutlier(events, "largest client-tick outlier", tickOutlier);
        }
    }

    private static List<Trace> readMethodTraces(List<RecordedEvent> events) {
        List<Trace> traces = new ArrayList<>();
        for (RecordedEvent event : events) {
            if (!event.getEventType().getName().equals("jdk.MethodTrace")) {
                continue;
            }
            RecordedMethod method = event.getValue("method");
            traces.add(new Trace(
                method.getType().getName(),
                method.getName(),
                event.getStartTime(),
                event.getEndTime(),
                event.getDuration().toNanos() / 1_000_000.0
            ));
        }
        return traces;
    }

    private static List<Frame> buildFrames(List<Trace> traces, List<Trace> phases, Instant usableStart) {
        List<Frame> frames = new ArrayList<>();
        for (Trace total : traces) {
            if (!total.is("net.minecraft.client.Minecraft", "runTick") || total.start.isBefore(usableStart)) {
                continue;
            }
            double render = 0.0;
            double display = 0.0;
            double tick = 0.0;
            for (Trace phase : phases) {
                if (phase.start.isBefore(total.start) || phase.end.isAfter(total.end)) {
                    continue;
                }
                if (phase.is("net.minecraft.client.renderer.GameRenderer", "render")) {
                    render += phase.ms;
                } else if (phase.is("com.mojang.blaze3d.platform.Window", "updateDisplay")) {
                    display += phase.ms;
                } else if (phase.is("net.minecraft.client.Minecraft", "tick")) {
                    tick += phase.ms;
                }
            }
            frames.add(new Frame(total, render, display, tick));
        }
        return frames;
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static void printOutlier(List<RecordedEvent> events, String label, Frame frame) {
        if (frame == null) {
            return;
        }
        System.out.printf(
            "%nRender-thread samples for the %s (%s total=%.2f ms render=%.2f ms display=%.2f ms tick=%.2f ms):%n",
            label,
            TIME.format(frame.total.start),
            frame.total.ms,
            frame.renderMs,
            frame.displayMs,
            frame.tickMs
        );
        printSamples(events, frame.total);
    }

    private static void printSamples(List<RecordedEvent> events, Trace interval) {
        for (RecordedEvent event : events) {
            String type = event.getEventType().getName();
            if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) {
                continue;
            }
            RecordedThread thread = event.getThread("sampledThread");
            if (thread == null
                || !"Render thread".equals(thread.getJavaName())
                || event.getStartTime().isBefore(interval.start)
                || !event.getStartTime().isBefore(interval.end)) {
                continue;
            }
            System.out.printf("  %s %s%n", TIME.format(event.getStartTime()), type.substring("jdk.".length()));
            RecordedStackTrace trace = event.getStackTrace();
            if (trace != null) {
                trace.getFrames().stream().limit(24).forEach(recordedFrame -> System.out.printf(
                    "      %s.%s%n",
                    recordedFrame.getMethod().getType().getName(),
                    recordedFrame.getMethod().getName()
                ));
            }
        }
    }
}
